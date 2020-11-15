import com.google.common.collect.Sets;
import com.rits.cloning.Cloner;
import ilog.concert.IloException;
import ilog.concert.IloNumExpr;
import ilog.concert.IloNumVar;
import ilog.concert.IloRange;
import ilog.cplex.IloCplex;
import org.apache.commons.math.util.MathUtils;
import org.graphstream.algorithm.coloring.WelshPowell;
import org.graphstream.graph.Graph;
import org.graphstream.graph.Node;
import org.graphstream.graph.implementations.SingleGraph;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.function.ToDoubleFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class CplexBnC {

    private static final int TIME_LIMIT_MINUTE = 60;
    private static final Logger log = Logger.getLogger(CplexBnC.class.getName());
    private static final double EPSI = 10e-6;
    private static double startTime;
    private static double interruptTime;
    private static String[] testData = {/*"c-fat200-1", "c-fat200-2", "c-fat200-5", "c-fat500-1", "c-fat500-10",
            "c-fat500-2", "c-fat500-5", "MANN_a9", "hamming6-2", "hamming6-4",*/ "gen200_p0.9_44", /*"gen200_p0.9_55",*/
            "san200_0.7_1", /*"san200_0.7_2", "san200_0.9_1", "san200_0.9_2",*/ "san200_0.9_3", "sanr200_0.7"/*, "C125.9",
            "keller4", "brock200_1", "brock200_2", "brock200_3", "brock200_4", "p_hat300-1", "p_hat300-2"*/};

    private static IloCplex cplex;
    private static Graph graph;

    private static int bestSolution = 0;
    private static int heuristicSolution = 0;
    private static double[] bestVariables;
    private static IloNumVar[] x;
    private static List<Node> coloredSortedNodes;

    private static Map<IloRange, Set<Node>> map = new HashMap<>();

    public static void main(String[] args) throws IOException {
        log.setLevel(Level.INFO);
        for (String fileName : testData) {
            boolean interrupted = false;
            try {
                solve(fileName);
            } catch (InterruptedException e) {
                interrupted = true;
            } finally {
                StringBuilder stringBuilder = new StringBuilder();
                stringBuilder.append(String.format("Graph: %s\n", fileName));
                stringBuilder.append(String.format("Best founded solution: %d\n", bestSolution));
                stringBuilder.append(String.format("Heuristic solution: %d\n", heuristicSolution));
                stringBuilder.append(String.format("Interrupted: %s\n", interrupted ? "True" : "False"));
                stringBuilder.append(String.format("Elapsed time: %.2f seconds\n", (System.currentTimeMillis() - startTime) / 1000F));
                LinkedList<Integer> indexes = new LinkedList<>();
                if (bestVariables != null) {
                    for (int i = 0; i < bestVariables.length; i++) {
                        if (bestVariables[i] == 1) {
                            indexes.add(i + 1);
                        }
                    }
                    stringBuilder.append(String.format("Variables: %s\n", Arrays.toString(indexes.toArray(new Integer[0]))));
                }
                stringBuilder.append("==========================\n");
                String str = stringBuilder.toString();
                Files.write(Paths.get("src\\main\\resources\\results.txt"), str.getBytes(), StandardOpenOption.APPEND);
                System.out.println(str);
                reset();
            }
        }
    }

    public static void reset() {
        bestSolution = 0;
        heuristicSolution = 0;
        bestVariables = null;
        graph = null;
        cplex = null;
        coloredSortedNodes = null;
        map.clear();
    }

    public static void solve(String file) throws InterruptedException {
        try {
            graph = loadGraph(file);
            x = initModel();
            bestSolution = initialHeuristic();
            startTime = System.currentTimeMillis();
            interruptTime = startTime + (TIME_LIMIT_MINUTE * 60 * 1000);
            BnC();
        } catch (IloException e) {
            e.printStackTrace();
        }
    }

    public static void BnC() throws IloException, InterruptedException {
        if (System.currentTimeMillis() >= interruptTime) {
            throw new InterruptedException();
        }
        cplex.solve();
        System.out.println("size = " + map.size());
        double newObjective = cplex.getObjValue();
        double[] newVariables;
        if (isWorseSolution(newObjective)) {
            if (log.isLoggable(Level.FINE)) {
                log.fine("New solution is worse. Return.");
            }
            return;
        }

        double prevScore = Double.MAX_VALUE;
        double[] separateVariables = cplex.getValues(x);
        double separateObjective = cplex.getObjValue();
        while (true) {
            //System.out.println(Arrays.toString(separateVariables));
            System.out.println(separateObjective);
            Set<Node> constraints = separate(separateVariables);
            System.out.println(constraints);
            if (constraints != null) {
                addConstraint(constraints, x);
            } else {
                break;
            }
            cplex.solve();
            double z = cplex.getObjValue();
            if (isWorseSolution(z)) {
                return;
            }
            if (prevScore - z < 0.001) {
                System.out.println(String.format("Bad: %f", prevScore - z));
                break;
            } else {
                prevScore = z;
                separateObjective = cplex.getObjValue();
                separateVariables = cplex.getValues(x);
            }

        }
        newObjective = cplex.getObjValue();
        newVariables = cplex.getValues(x);
        if (log.isLoggable(Level.INFO)) {
            log.info(String.format("New solution = %f, best solution = %d", newObjective, bestSolution));
        }

        if (isIntegerSolution(newVariables)) {
            if (log.isLoggable(Level.INFO)) {
                log.info(String.format("Found better solution = %f, variables: \n %s", newObjective, Arrays.toString(newVariables)));
            }
            bestSolution = (int) Math.round(newObjective);
            bestVariables = newVariables;
            return;
        }

/*        if (map.size() > CONST_LIMIT_MINUTE) {
            List<IloRange> collect = map.keySet().stream().sorted(Comparator.comparingDouble((ToDoubleFunction<IloRange>) iloRange -> {
                try {
                    return cplex.getSlack(iloRange);
                } catch (IloException e) {
                    e.printStackTrace();
                    return -1;
                }
            }).reversed()).collect(Collectors.toList());

            for (int i = 0; i < collect.size() - CONST_LIMIT_MINUTE; i++) {
                IloRange iloRange = collect.get(i);
                //System.out.println(String.format("slack = %f, removing \n %s", cplex.getSlack(iloRange), iloRange));
                cplex.remove(iloRange);
                map.remove(iloRange);
            }
        }*/

        int iForBranching = branching(newVariables);
        if (log.isLoggable(Level.FINE)) {
            log.fine(String.format("Branching by %d", iForBranching));
        }
        IloRange UB = addBound(iForBranching, newVariables, true);
        BnC();
        cplex.remove(UB);
        if (log.isLoggable(Level.FINE)) {
            log.fine(String.format("Constraint removed by %s", UB.toString()));
        }
        IloRange LB = addBound(iForBranching, newVariables, false);
        BnC();
        cplex.remove(LB);
        if (log.isLoggable(Level.FINE)) {
            log.fine(String.format("Constraint removed by %s", LB.toString()));
        }
    }

    public static int branching(double[] newVariables) {
        return coloredSortedNodes.stream()
                .filter(node -> MathUtils.compareTo(Math.round(newVariables[node.getIndex()]), newVariables[node.getIndex()], EPSI) != 0)
                .map(Node::getIndex)
                .findFirst().orElse(-1);
    }

    public static IloRange addBound(int branch, double[] newVariables, boolean isUpper) throws IloException {
        IloRange constraint;
        if (isUpper) {
            double ceil = Math.ceil(newVariables[branch]);
            constraint = cplex.range(ceil, x[branch], ceil);

        } else {
            double floor = Math.floor(newVariables[branch]);
            constraint = cplex.range(floor, x[branch], floor);
        }
        cplex.add(constraint);
        if (log.isLoggable(Level.FINE)) {
            log.fine(String.format("Added constraint %s", constraint.toString()));
        }
        return constraint;
    }

    public static int initialHeuristic() {
        Cloner cloner = new Cloner();
        Graph graphCopy = cloner.deepClone(graph);
        Set<Node> K = new HashSet<>();
        while (graphCopy.getNodeCount() > 0) {
            int indexOfMaxDegree = StreamSupport.stream(graphCopy.spliterator(), false)
                    .max(Comparator.comparingInt(Node::getDegree)).map(Node::getIndex).orElse(-1);
            if (indexOfMaxDegree != -1 && !K.contains(graphCopy.getNode(indexOfMaxDegree))) {
                K.add(graphCopy.getNode(indexOfMaxDegree));
                Set<Node> nodesToRemove = new HashSet<>();
                for (Node nextNode : graphCopy) {
                    if (!graphCopy.getNode(indexOfMaxDegree).hasEdgeBetween(nextNode)) {
                        nodesToRemove.add(nextNode);
                    }
                }
                for (Node node : nodesToRemove) {
                    graphCopy.removeNode(node);
                }
            }
        }
        heuristicSolution = K.size();
        bestVariables = new double[graph.getNodeCount()];
        for (int i = 0; i < bestVariables.length; i++) {
            int finalI = i;
            bestVariables[i] = K.stream().anyMatch(node -> (Integer.parseInt(node.getId()) - 1) == finalI) ? 1 : 0;
        }

        return K.size();
    }

    public static IloNumVar[] initModel() throws IloException {
        cplex = new IloCplex();
        cplex.setOut(null);
        String[] names = new String[graph.getNodeCount()];
        for (int i = 0; i < names.length; i++) {
            names[i] = String.format("x_%d", i);
        }
        IloNumVar[] xNumVars = cplex.numVarArray(graph.getNodeCount(), 0L, 1L, names);
        int maxDegree = 0;
        for (int i = 0; i < graph.getNodeCount(); i++) {
            if (graph.getNode(i).getDegree() > maxDegree) {
                maxDegree = graph.getNode(i).getDegree();
            }

            for (int j = 0; j < graph.getNodeCount(); j++) {
                if (i != j && !graph.getNode(i).hasEdgeBetween(j)) {
                    IloRange lessEqualsRange = cplex.range(0, cplex.sum(xNumVars[i], xNumVars[j]), 1);
                    lessEqualsRange.setName("" + i + " " + j);
                    cplex.add(lessEqualsRange);
                }
            }

            List<Set<Node>> independentSets = findIndependentSets(graph.getNode(i));
            for (Set<Node> independentSet : independentSets) {
                addConstraint(independentSet, xNumVars);
            }
        }

        cplex.add(cplex.le(cplex.sum(xNumVars), maxDegree));

        cplex.add(xNumVars);
        cplex.add(cplex.maximize(cplex.sum(xNumVars)));
        return xNumVars;
    }

    public static void addConstraint(Set<Node> independentSet, IloNumVar[] x) throws IloException {
        for (Set<Node> constraint : map.values()) {
            if (constraint.containsAll(independentSet)) {
                System.out.println("contains");
                return;
            }
        }
        List<Integer> indexes = independentSet.stream().map(Node::getIndex).collect(Collectors.toList());
        IloNumExpr[] exprs = new IloNumExpr[graph.getNodeCount()];
        for (int j = 0; j < graph.getNodeCount(); j++) {
            exprs[j] = cplex.prod(x[j], indexes.contains(j) ? 1 : 0);
        }
        IloRange range = cplex.range(0, cplex.sum(exprs), 1);
        cplex.add(range);
        map.put(range, independentSet);
    }

    public static Graph loadGraph(String fileName) {
        Graph graph = new SingleGraph(fileName);
        try {
            BufferedReader reader = new BufferedReader(new FileReader("src\\main\\resources\\" + fileName + ".clq"));
            String line = reader.readLine();
            while (line != null) {
                if (line.startsWith("p")) {
                    String[] s = line.split(" ");
                    int vertexCount = Integer.parseInt(s[2]);
                    for (int i = 0; i < vertexCount; i++) {
                        graph.addNode(String.valueOf(i + 1));
                    }
                }
                if (line.startsWith("e")) {
                    String[] s = line.split(" ");
                    String edge1 = s[1];
                    String edge2 = s[2];
                    graph.addEdge(edge1 + "-" + edge2, edge1, edge2);
                }
                line = reader.readLine();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        WelshPowell color = new WelshPowell("color");
        color.init(graph);
        color.compute();
        coloredSortedNodes = StreamSupport.stream(graph.spliterator(), false)
                .sorted(Comparator.comparingInt(node -> ((Node) node).getAttribute("color")).reversed()).collect(Collectors.toList());

        return graph;
    }

    public static List<Set<Node>> findIndependentSets(Node inputNode) {
        Set<Node> newSet = new HashSet<>();
        LinkedList<Set<Node>> allSets = new LinkedList<>();
        Set<Node> usedNodes = new HashSet<>();
        while (true) {
            for (Node node : newSet) {
                if (inputNode.getIndex() != node.getIndex()) {
                    usedNodes.add(node);
                }
            }
            newSet = getMaximalIndependentSet(usedNodes, inputNode);
            if (newSet.size() <= 1) {
                break;
            }
            allSets.add(newSet);
        }

        return allSets;
    }

    public static Set<Node> getMaximalIndependentSet(Set<Node> usedNodes, Node inputNode) {
        HashSet<Node> independentNodes = new HashSet<>();
        independentNodes.add(inputNode);
        HashSet<Node> availableNodes = new HashSet<>();
        for (int i = inputNode.getIndex(); i < graph.getNodeCount(); i++) {
            availableNodes.add(graph.getNode(i));
        }

        HashSet<Node> neighbors = getNeighbors(inputNode, availableNodes, usedNodes);

        Set<Node> notNeighbors = new HashSet<>(Sets.difference(availableNodes, Sets.union(neighbors, independentNodes)));

        while (notNeighbors.size() > 0) {
            Node node = notNeighbors.iterator().next();
            independentNodes.add(node);
            notNeighbors.removeAll(Sets.union(getNeighbors(node, availableNodes, usedNodes), independentNodes));
        }

        return independentNodes;
    }

    public static Set<Node> separate(final double[] currentSolution) {
        long startTime = System.currentTimeMillis();
        //Set<Set<Node>> constr = new HashSet<>();
        //Map<String, Object> result = getWeightedIndependentSet(currentSolution);
        PriorityQueue<Node> queue = createQueue(currentSolution);
        Collection<Node> availableNodes = graph.getNodeSet();
        Set<Node> set = new HashSet<>();
        Node node = queue.poll();
        double sum = 0D;
        while (node != null) {
            set.add(node);
            sum += currentSolution[node.getIndex()];
            HashSet<Node> neighbors = getNeighbors(node, availableNodes, new HashSet<>());
            queue.removeAll(neighbors);
            node = queue.poll();
        }
        System.out.println(String.format("Separate time: %d seconds, sum %s\n", (System.currentTimeMillis() - startTime), sum));
        System.out.println(set);
        if (MathUtils.compareTo(sum, 1, EPSI) > 0) {
            return set;
        } else {
            return null;
        }
    }

    public static PriorityQueue<Node> createQueue(double[] currentSolution) {
        PriorityQueue<Node> queue = new PriorityQueue<>(currentSolution.length,
                Comparator.comparingDouble((ToDoubleFunction<Node>) vertex -> currentSolution[vertex.getIndex()] / vertex.getDegree()).reversed());
        queue.addAll(graph.getNodeSet());
        return queue;
    }

    public static HashSet<Node> getNeighbors(Node inputNode, Collection<Node> availableNodes, Set<Node> usedNodes) {
        HashSet<Node> neighbors = new HashSet<>();
        for (Node node : availableNodes) {
            if (inputNode.hasEdgeBetween(node) || usedNodes.contains(node)) {
                neighbors.add(node);
            }
        }
        return neighbors;
    }

    public static boolean isIntegerSolution(double[] solution) {
        for (double var : solution) {
            if (!isCloseToInteger(var)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isCloseToInteger(double var) {
        long rounded = Math.round(var);
        return MathUtils.compareTo(rounded, var, EPSI) == 0;
    }

    public static boolean isWorseSolution(double newSolution) {
        if (Math.abs(newSolution - Math.round(newSolution)) < EPSI) {
            return MathUtils.compareTo(Math.round(newSolution), bestSolution, EPSI) <= 0;
        }
        return MathUtils.compareTo(Math.floor(newSolution), bestSolution, EPSI) <= 0;
    }
}
