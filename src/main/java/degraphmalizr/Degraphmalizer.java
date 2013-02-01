package degraphmalizr;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import javax.inject.Inject;

import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.client.Client;
import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Optional;
import com.google.common.collect.Iterables;
import com.google.inject.Provider;
import com.tinkerpop.blueprints.Direction;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Graph;
import com.tinkerpop.blueprints.Vertex;

import configuration.*;
import configuration.javascript.JSONUtilities;
import degraphmalizr.jobs.*;
import elasticsearch.QueryFunction;
import elasticsearch.ResolvedPathElement;
import exceptions.DegraphmalizerException;
import graphs.GraphQueries;
import graphs.ops.Subgraph;
import graphs.ops.SubgraphManager;
import modules.bindingannotations.Degraphmalizes;
import modules.bindingannotations.Fetches;
import modules.bindingannotations.Recomputes;
import trees.Pair;
import trees.Tree;
import trees.Trees;

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

    protected final Provider<Configuration> cfgProvider;

    final ObjectMapper objectMapper = new ObjectMapper();

	@Inject
	public Degraphmalizer(Client client, SubgraphManager subgraphmanager, Graph graph,
                          @Degraphmalizes ExecutorService degraphmalizeQueue,
                          @Fetches ExecutorService fetchQueue,
                          @Recomputes ExecutorService recomputeQueue,
                          QueryFunction queryFunction,
                          Provider<Configuration> configProvider)
	{
        this.fetchQueue = fetchQueue;
        this.recomputeQueue = recomputeQueue;
        this.degraphmalizeQueue = degraphmalizeQueue;

        this.graph = graph;
        this.subgraphmanager = subgraphmanager;
		this.client = client;
        this.cfgProvider = configProvider;
        this.queryFn = queryFunction;
	}

    @Override
    public final List<DegraphmalizeAction> degraphmalize(DegraphmalizeActionType actionType, ID id, DegraphmalizeStatus callback) throws DegraphmalizerException
    {
        final ArrayList<DegraphmalizeAction> actions = new ArrayList<DegraphmalizeAction>();

        for(TypeConfig cfg : configsFor(id.index(), id.type()))
        {
            // construct the action object
            final DegraphmalizeAction action = new DegraphmalizeAction(actionType, id, cfg, callback);

            // convert object into task and queue
            action.result = degraphmalizeQueue.submit(degraphmalizeJob(action));

            actions.add(action);
        }

        return actions;
    }

    private List<TypeConfig> configsFor(String index, String type) throws DegraphmalizerException
    {
        final ArrayList<TypeConfig> configs = new ArrayList<TypeConfig>();

        // TODO refactor
        for(IndexConfig i : cfgProvider.get().indices().values())
            for(TypeConfig t : i.types().values())
                if(index.equals(t.sourceIndex()) && type.equals(t.sourceType()))
                {
                    log.debug("Matching to request for /{}/{} --> /{}/{}",
                            new Object[]{t.sourceIndex(), t.sourceType(), i.name(), t.name()});

                    configs.add(t);
                }

        return configs;
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
                        result = doUpdate(action);
                        break;
                    case DELETE:
                        result = doDelete(action);
                        break;
                    default:
                        throw new DegraphmalizerException("Don't know how to handle action type " + actionType + " for action " + action);
                }

                return result;
            }
		};
	}

    private JsonNode doUpdate(DegraphmalizeAction action) throws Exception {
        try {
            log.info("Processing request '{}', for id={}", action.hash().toString(), action.id());

            // Get document from elasticsearch
            setDocument(action);

            generateSubgraph(action);

            final List<RecomputeAction> recomputeActions = determineRecomputeActions(action);
            final List<Future<Optional<IndexResponse>>> results = recomputeAffectedDocuments(recomputeActions);

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

    // TODO refactor (dubbeling met doUpdate) (DGM-44)
    private JsonNode doDelete(DegraphmalizeAction action) throws Exception {
        try {
            List<RecomputeAction> recomputeActions = determineRecomputeActions(action);

            final Subgraph sg = subgraphmanager.createSubgraph(action.id());
            subgraphmanager.deleteSubgraph(sg);

            final List<Future<Optional<IndexResponse>>> results = recomputeAffectedDocuments(recomputeActions);

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

    private void setDocument(DegraphmalizeAction action) throws InterruptedException, ExecutionException, DegraphmalizerException, IOException {
        final ID id = action.id();

        // get the source document from Elasticsearch
        final GetResponse resp = client.prepareGet(id.index(), id.type(), id.id()).execute().get();

        if(!resp.exists())
            throw new DegraphmalizerException("Document does not exist");

        //TODO: shouldn't this be: resp.version() > id.version()
        log.debug("Request has version " + id.version() + " and current es document has version " + resp.version());
        if(resp.version() != id.version())
            throw new DegraphmalizerException("Query expired, current version is " + resp.version());

        // alright, we have the right source document, so let's start processing.

        // first we parse the document into a JsonNode tree
        final JsonNode jsonNode = objectMapper.readTree(resp.getSourceAsString());

        action.setDocument(jsonNode);
    }

    private void generateSubgraph(DegraphmalizeAction action) throws DegraphmalizerException {
        final ID id = action.id();

        if(log.isTraceEnabled())
            GraphQueries.dumpGraph(graph);

        // extract the graph elements
        log.debug("Extracting graph elements");
        
        final Subgraph sg = subgraphmanager.createSubgraph(id);
        action.typeConfig().extract(action, sg);
        
        log.debug("Completed extraction of graph elements");
        
        subgraphmanager.commitSubgraph(sg);
        log.debug("Committed subgraph to graph");
        
        if(log.isTraceEnabled())
            GraphQueries.dumpGraph(graph);

    }

    private List<Future<Optional<IndexResponse>>> recomputeAffectedDocuments(List<RecomputeAction> recomputeActions) throws DegraphmalizerException, InterruptedException {
        // create Callable from the actions
        // TODO call 'recompute started' for each action to update the status
        final ArrayList<Callable<Optional<IndexResponse>>> jobs = new ArrayList<Callable<Optional<IndexResponse>>>();
        for(RecomputeAction r : recomputeActions)
            jobs.add(recomputeDocument(r));

        // recompute all affected documents and wait for results
        // TODO call 'recompute finished' for each action
        return recomputeQueue.invokeAll(jobs);
    }

    private ArrayList<RecomputeAction> determineRecomputeActions(DegraphmalizeAction action) throws DegraphmalizerException {
        final ID id = action.id();

        // we now start traversals for each walk to find documents affected by this change
        final Vertex root = GraphQueries.findVertex(graph, id);
        if (root == null)
            // TODO this shouldn't occur, because the subgraph implicitly commits a vertex to the graph
            throw new DegraphmalizerException("No node for document " + id);

        final ArrayList<RecomputeAction> recomputeActions = new ArrayList<RecomputeAction>();

        // we add ourselves as the first job in the list
        recomputeActions.add(new RecomputeAction(action, action.typeConfig(), root));

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

                // TODO add ID to action object, avoid graph queries at some later point
                final ID v_id = GraphQueries.getID(v);

                // we already know this document does not exist in ES, skip
                if(v_id.version() == 0)
                    continue;

                // alright, mark for computation
                recomputeActions.add(new RecomputeAction(action, action.typeConfig(), v));
            }
        }

        log.debug("Action {} caused recompute for {} documents", action.hash(), recomputeActions.size());
        return recomputeActions;
    }

    /**
     * This procuedure actually performes the recompute of individual documents. It performs transformation, applies walks
     * and inserts/updates the target document.
     *
     * @param action represents the source document and recompute configuration.
     * @return the ES IndexRespons to the insert of the target document.
     */
    private Callable<Optional<IndexResponse>> recomputeDocument(final RecomputeAction action)
    {
        return new Callable<Optional<IndexResponse>>()
        {
            @Override
            public Optional<IndexResponse> call() throws DegraphmalizerException
            {
                try
                {
                    // ideally, this is handled in a monad, but with this boolean we keep track of failures
                    boolean isAbsent = false;

                    /*
                    * Now we are going to iterate over all the walks configured for this input document. For each walk:
                    * - We fetch a tree of children non-recursively from our document in the inverted direction of the walk, as Graph vertices
                    * - We convert the tree of vertices to a tree of ElasticSearch documents
                    * - We call the reduce() method for this walk, with the tree of documents as argument.
                    * - We collect the result.
                     */

                    final HashMap<String, JsonNode> walkResults = new HashMap<String, JsonNode>();

                    if (! action.typeConfig.walks().entrySet().isEmpty()) {
                        for(Map.Entry<String,WalkConfig> walkCfg : action.typeConfig.walks().entrySet())
                        {
                            // walk graph, and fetch all the children in the opposite direction of the walk
                            final Tree<Pair<Edge,Vertex>> tree =
                                    GraphQueries.childrenFrom(graph, action.root, walkCfg.getValue().direction().opposite());

                            // write size information to log
                            if(log.isDebugEnabled())
                            {
                                final int size = Iterables.size(Trees.bfsWalk(tree));
                                log.debug("Retrieving {} documents from ES", size);
                            }

                            // get all documents in the tree from Elasticsearch (in parallel)
                            final Tree<Optional<ResolvedPathElement>> docTree = Trees.pmap(fetchQueue, queryFn, tree);

                            // if some value is absent from the tree, abort the computation
                            final Optional<Tree<ResolvedPathElement>> fullTree = Trees.optional(docTree);

                            // TODO split various failure modes
                            if(!fullTree.isPresent())
                            {
                                isAbsent = true;
                                break;
                            }

                            // reduce each property to a value based on the walk result
                            for(final Map.Entry<String,? extends PropertyConfig> propertyCfg : walkCfg.getValue().properties().entrySet())
                                walkResults.put(propertyCfg.getKey(), propertyCfg.getValue().reduce(fullTree.get()));
                        }

                        // something failed, so we abort the whole re-computation
                        if(isAbsent)
                        {
                            // TODO refactor the action, it should have the ID
                            log.debug("Some results were absent, aborting re-computation for " + GraphQueries.getID(action.root));
                            return Optional.absent();
                        }
                    }



                    /*
                    * Now we are going to fetch the current ElasticSearch document,
                    * - Transform it, if transformation is required
                    * - Add the walk properties
                    * - Add a reference to the source document.
                    * - And store it as target document type in target index.
                     */

                    //todo: what if Optional.absent??
                    //todo: queryFn.apply may produce null
                    ResolvedPathElement root  = queryFn.apply(new Pair<Edge, Vertex>(null, action.root) ).get();
                    // fetch the current ES document
                    //todo: what if optional absent?
                    final JsonNode rawDocument = objectMapper.readTree(root.getResponse().get().getSourceAsString());

                    // pre-process document using javascript
                    final JsonNode doc = action.typeConfig.transform(rawDocument);

                    if(!doc.isObject())
                    {
                        log.debug("Root of processed document is not a JSON object (ie. it's a value), we are not adding the reduced properties");
                        return Optional.absent();
                    }

                    // add the results to the document
                    for(Map.Entry<String,JsonNode> e : walkResults.entrySet())
                        ((ObjectNode)doc).put(e.getKey(), e.getValue());

                    // write the result document to the target index
                    final String targetIndex = action.typeConfig.targetIndex();
                    final String targetType = action.typeConfig.targetType();
                    final ID id = GraphQueries.getID(action.root);
                    final String idString = id.id();

                    // write the source version to the document
                    ((ObjectNode)doc).put("_fromSource", JSONUtilities.toJSON(objectMapper, id));

                    // write document to Elasticsearch
                    final String documentSource = doc.toString();
                    final IndexResponse ir = client.prepareIndex(targetIndex, targetType, idString).setSource(documentSource)
                            .execute().actionGet();

                    log.debug("Written /{}/{}/{}, version={}", new Object[]{targetIndex, targetType, idString, ir.version()});

                    log.debug("Content: {}", documentSource);
                    return Optional.of(ir);
                }
                catch (Exception e)
                {
                    // TODO store and retrieve from action object, see TODO above line 215, at time of this commit ;^)
                    final ID id = GraphQueries.getID(action.root);
                    final String msg = "Exception in recomputation phase for id " + id;
                    log.error(msg, e);

                    throw new DegraphmalizerException(msg, e);
                }
            }
        };
    }

    private ObjectNode getStatusJsonNode(List<Future<Optional<IndexResponse>>> results) throws InterruptedException, ExecutionException {
        final Optional<IndexResponse> ourResult = results.get(0).get();
        final ObjectNode n = objectMapper.createObjectNode();
        if(ourResult.isPresent())
        {
            n.put("success", true);
            n.put("version", ourResult.get().version());
        }
        else
            n.put("success", false);
        return n;
    }
}
