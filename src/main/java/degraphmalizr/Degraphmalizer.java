package degraphmalizr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.Iterables;
import com.google.inject.Provider;
import com.tinkerpop.blueprints.*;
import configuration.*;
import configuration.javascript.JSONUtilities;
import degraphmalizr.degraphmalize.*;
import degraphmalizr.recompute.*;
import elasticsearch.QueryFunction;
import exceptions.DegraphmalizerException;
import graphs.GraphQueries;
import graphs.ops.Subgraph;
import graphs.ops.SubgraphManager;
import modules.bindingannotations.*;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.client.Client;
import org.slf4j.Logger;
import trees.*;

import javax.inject.Inject;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

public class Degraphmalizer implements Degraphmalizr
{
    @Inject
	Logger log;

    protected final Client client;

    protected final Graph graph;
    protected final SubgraphManager subgraphmanager;

    protected final ExecutorService degraphmalizeQueue;
    protected final ExecutorService recomputeQueue;
    protected final ExecutorService fetchQueue;

    protected final QueryFunction queryFn;
    protected final Recomputer recomputer;

    protected final Provider<Configuration> cfgProvider;

    final ObjectMapper objectMapper = new ObjectMapper();

	@Inject
	public Degraphmalizer(Client client, SubgraphManager subgraphmanager, Graph graph,
                          @Degraphmalizes ExecutorService degraphmalizeQueue,
                          @Fetches ExecutorService fetchQueue,
                          @Recomputes ExecutorService recomputeQueue,
                          QueryFunction queryFunction,
                          Recomputer recomputer,
                          Provider<Configuration> configProvider)
	{
        this.fetchQueue = fetchQueue;
        this.recomputeQueue = recomputeQueue;
        this.degraphmalizeQueue = degraphmalizeQueue;
        this.graph = graph;
        this.subgraphmanager = subgraphmanager;
		this.client = client;
        this.recomputer = recomputer;
        this.cfgProvider = configProvider;
        this.queryFn = queryFunction;
	}

    @Override
    public final List<DegraphmalizeAction> degraphmalize(DegraphmalizeActionType actionType, ID id, DegraphmalizeStatus callback) throws DegraphmalizerException
    {
        final ArrayList<DegraphmalizeAction> actions = new ArrayList<DegraphmalizeAction>();

        for(TypeConfig cfg : Configurations.configsFor(cfgProvider.get(), id.index(), id.type()))
        {
            log.debug("Matching to request for /{}/{} --> /{}/{}",
                    new Object[]{cfg.sourceIndex(), cfg.sourceType(), cfg.targetIndex(), cfg.targetType()});

            // construct the action object
            final DegraphmalizeAction action = new DegraphmalizeAction(actionType, id, cfg, callback);

            // convert object into task and queue
            action.result = degraphmalizeQueue.submit(degraphmalizeJob(action));

            actions.add(action);
        }

        return actions;
    }

    private Callable<JsonNode> degraphmalizeJob(final DegraphmalizeAction action) throws DegraphmalizerException
	{
		return new Callable<JsonNode>()
		{
			@Override
			public JsonNode call() throws Exception
			{
                final JsonNode result;
                final DegraphmalizeActionType actionType = action.type();

                switch(actionType) {
                    case UPDATE:
                        return doUpdate(action);

                    case DELETE:
                        return doDelete(action);

                    default:
                        throw new DegraphmalizerException("Don't know how to handle action type " + actionType + " for action " + action);
                }
            }
		};
	}

    private JsonNode doUpdate(DegraphmalizeAction action) throws Exception
    {
        try
        {
            log.info("Processing request for id {}", action.id());

            // Get document from elasticsearch
            final JsonNode jsonNode = getDocument(action.id());

            // couldn't find source document, so we are done
            if (jsonNode == null)
            {
                // TODO cleanup status/result/action objects.
                action.status().complete(DegraphmalizeResult.success(action));
                return objectMapper.createObjectNode().put("success",true);
            }
            else
            {
                action.setDocument(jsonNode);

                generateSubgraph(action);

                final List<RecomputeRequest> recomputeRequests = determineRecomputeActions(action);
                final List<Future<RecomputeResult>> results = recomputeAffectedDocuments(recomputeRequests);

                // TODO refactor action class
                action.status().complete(DegraphmalizeResult.success(action));
                return getStatusJsonNode(results);
            }
        }
        catch (final DegraphmalizerException e)
        {
            final DegraphmalizeResult result = DegraphmalizeResult.failure(action, e);

            // report failure
            action.status().exception(result);

            // rethrow, this will captured by Future<>.get()
            throw e;
        }
        catch (final Exception e)
        {
            final DegraphmalizerException ex = new DegraphmalizerException("Unknown exception occurred", e);
            final DegraphmalizeResult result = DegraphmalizeResult.failure(action, ex);

            // report failure
            action.status().exception(result);

            // rethrow, this will captured by Future<>.get()
            throw e;
        }
    }

    // TODO refactor (dubbeling met doUpdate) (DGM-44)
    private JsonNode doDelete(DegraphmalizeAction action) throws Exception
    {
        try
        {
            List<RecomputeRequest> recomputeRequests = determineRecomputeActions(action);

            final Subgraph sg = subgraphmanager.createSubgraph(action.id());
            subgraphmanager.deleteSubgraph(sg);

            final List<Future<RecomputeResult>> results = recomputeAffectedDocuments(recomputeRequests);

            // TODO refactor action class
            action.status().complete(DegraphmalizeResult.success(action));

            return getStatusJsonNode(results);
        } catch (final DegraphmalizerException e) {
            final DegraphmalizeResult result = DegraphmalizeResult.failure(action, e);

            // report failure
            action.status().exception(result);

            // rethrow, this will captured by Future<>.get()
            throw e;
        } catch (final Exception e) {
            final DegraphmalizerException ex = new DegraphmalizerException("Unknown exception occurred", e);
            final DegraphmalizeResult result = DegraphmalizeResult.failure(action, ex);

            // report failure
            action.status().exception(result);

            // rethrow, this will captured by Future<>.get()
            throw e;
        }
    }

    private JsonNode getDocument(ID id) throws InterruptedException, ExecutionException, DegraphmalizerException, IOException {

        // get the source document from Elasticsearch
        final GetResponse resp = client.prepareGet(id.index(), id.type(), id.id()).execute().get();

        if (!resp.exists())
            return null;

        //TODO: shouldn't this be: resp.version() > id.version()
        log.debug("Request has version " + id.version() + " and current es document has version " + resp.version());
        if (resp.version() != id.version())
            throw new DegraphmalizerException("Query expired, current version is " + resp.version());

        return objectMapper.readTree(resp.getSourceAsString());
    }

    private void generateSubgraph(DegraphmalizeAction action) throws DegraphmalizerException {
        final ID id = action.id();

//        if(log.isTraceEnabled())
            GraphQueries.dumpGraph(graph);

        // extract the graph elements
        log.debug("Extracting graph elements");
        
        final Subgraph sg = subgraphmanager.createSubgraph(id);
        action.typeConfig().extract(action, sg);
        
        log.debug("Completed extraction of graph elements");
        
        subgraphmanager.commitSubgraph(sg);
        log.debug("Committed subgraph to graph");
        
//        if(log.isTraceEnabled())
            GraphQueries.dumpGraph(graph);

    }

    private List<Future<RecomputeResult>> recomputeAffectedDocuments(List<RecomputeRequest> recomputeRequests) throws DegraphmalizerException, InterruptedException {
        // create Callable from the actions
        // TODO call 'recompute started' for each action to update the status
        final ArrayList<Callable<RecomputeResult>> jobs = new ArrayList<Callable<RecomputeResult>>();
        for(RecomputeRequest r : recomputeRequests)
            jobs.add(recomputeDocument(r));

        // recompute all affected documents and wait for results
        // TODO call 'recompute finished' for each action
        return recomputeQueue.invokeAll(jobs);
    }

    private ArrayList<RecomputeRequest> determineRecomputeActions(DegraphmalizeAction action) throws DegraphmalizerException {
        final ID id = action.id();

        // we now start traversals for each walk to find documents affected by this change
        final Vertex root = GraphQueries.findVertex(graph, id);
        if (root == null)
            // TODO this shouldn't occur, because the subgraph implicitly commits a vertex to the graph
            throw new DegraphmalizerException("No node for document " + id);

        final ArrayList<RecomputeRequest> recomputeRequests = new ArrayList<RecomputeRequest>();

        // we add ourselves as the first job in the list
        final VID vid = new VID(root, id);
        recomputeRequests.add(new RecomputeRequest(vid, action.typeConfig()));

        for(WalkConfig walkCfg : action.typeConfig().walks().values())
        {
            // by walking in the opposite direction we can find all nodes affected by this change
            final Direction direction = walkCfg.direction().opposite();

            // traverse graph in the other direction, starting at the root
            log.debug("Computing tree in direction {}, starting at {}", direction, root);
            final Tree<Pair<Edge,Vertex>> tree = GraphQueries.childrenFrom(graph, root, direction);

            if(log.isDebugEnabled())
            {
                final int size = Iterables.size(Trees.bfsWalk(tree));
                log.debug("Found tree of size {}", size);
            }

            // create "dirty document" messages for each node in the tree
            for (Pair<Edge, Vertex> pathElement : Trees.bfsWalk(tree))
            {
                final Vertex v = pathElement.b;

                // skip the root of the tree, ie. ourselves:
                if(v.equals(root))
                    continue;

                final VID v_id = new VID(v);

                // we already know this document does not exist in ES, skip
                if(v_id.ID().version() == 0)
                    continue;

                // alright, mark for computation
                recomputeRequests.add(new RecomputeRequest(v_id, action.typeConfig()));
            }
        }

        log.debug("Action for id {} caused recompute for {} documents", action.id(), recomputeRequests.size());
        return recomputeRequests;
    }

    /**
     * This procuedure actually performes the recompute of individual documents. It performs transformation, applies walks
     * and inserts/updates the target document.
     *
     * @param action represents the source document and recompute configuration.
     * @return the ES IndexRespons to the insert of the target document.
     */
    private Callable<RecomputeResult> recomputeDocument(final RecomputeRequest action)
    {
        return new Callable<RecomputeResult>()
        {
            @Override
            public RecomputeResult call() throws DegraphmalizerException
            {
                // TODO pass callback
                final RecomputeCallback cb = new RecomputeCallback(){};
                return recomputer.recompute(action, cb);
            }
        };
    }

    private ObjectNode getStatusJsonNode(List<Future<RecomputeResult>> results) throws InterruptedException, ExecutionException
    {
        final RecomputeResult ourResult = results.get(0).get();

        if(ourResult.success().isPresent())
        {
            final ObjectNode n = objectMapper.createObjectNode();

            final RecomputeResult.Success success = ourResult.success().get();
            n.put("success", true);

            // write targetID using index reponse
            final IndexResponse ir = success.indexResponse();
            final ObjectNode targetID = objectMapper.createObjectNode();
            targetID.put("index", ir.index());
            targetID.put("type", ir.type());
            targetID.put("id", ir.id());
            targetID.put("version", ir.version());
            n.put("targetID", targetID);

            // write dictionary of properties and their values
            final ObjectNode properties = objectMapper.createObjectNode();
            for(Map.Entry<String,JsonNode> entry : success.properties().entrySet())
                properties.put(entry.getKey(), entry.getValue());
            n.put("properties", properties);

            // write dictionary of properties and their values
            n.put("sourceDocumentAfterTransform", success.sourceDocument());

            return n;
        }

        if(ourResult.exception().isPresent())
        {
            final Throwable t = ourResult.exception().get();
            final ObjectNode n = JSONUtilities.renderException(objectMapper, t);
            n.put("success", false);
            return n;
        }

        if(ourResult.expired().isPresent())
        {
            final ObjectNode n = objectMapper.createObjectNode();
            final ArrayNode ids = objectMapper.createArrayNode();
            n.put("success", false);
            for(ID id : ourResult.expired().get())
                ids.add(JSONUtilities.toJSON(objectMapper, id));
            return n;
        }

        final ObjectNode n = objectMapper.createObjectNode();
        n.put("success", false);
        n.put("status", ourResult.status().name());
        return n;
    }
}