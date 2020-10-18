import com.google.common.collect.Sets;
import com.rits.cloning.Cloner;
import ilog.concert.IloException;
import ilog.concert.IloNumExpr;
import ilog.concert.IloNumVar;
import ilog.concert.IloRange;
import ilog.cplex.IloCplex;
import org.apache.commons.math.util.MathUtils;
import org.graphstream.algorithm.coloring.WelshPowell;
import org.graphstream.graph.Element;
import org.graphstream.graph.Graph;
import org.graphstream.graph.Node;
import org.graphstream.graph.implementations.SingleGraph;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class CPLEXExample {

    private static final int TIME_LIMIT_MINUTE = 60;
    private static final Logger log = Logger.getLogger(CPLEXExample.class.getName());
    private static final double EPSI = 10e-6;
    private static double startTime;
    private static double interruptTime;
    private static String[] testData = {"c-fat200-1", "c-fat200-2", "c-fat200-5", "c-fat500-1", "c-fat500-10",
            "c-fat500-2", "c-fat500-5", "MANN_a9", "hamming6-2", "hamming6-4", "gen200_p0.9_44", "gen200_p0.9_55",
            "san200_0.7_1", "san200_0.7_2", "san200_0.9_1", "san200_0.9_2", "san200_0.9_3", "sanr200_0.7", "C125.9",
            "keller4", "brock200_1", "brock200_2", "brock200_3", "brock200_4", "p_hat300-1", "p_hat300-2"};

    private static IloCplex cplex;
    private static Graph graph;

    private static int bestSolution = 0;
    private static int heuristicSolution = 0;
    private static double[] bestVariables;
    private static IloNumVar[] x;
    private static byte[][] adjacencyMatrix;
    private static List<Node> coloredSortedNodes;

    public static void main(String[] args) throws IOException {
        log.setLevel(Level.OFF);
        //File ouputFile = createOuputFile();
        for (String fileName : testData) {
            boolean interrupted = false;
            try {
                System.out.println("loading file " + fileName);
                solveGraph(fileName);
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

                bestSolution = 0;
                heuristicSolution = 0;
                bestVariables = null;
                adjacencyMatrix = null;
                graph = null;
                cplex = null;
                coloredSortedNodes = null;
            }
        }
    }

    public static void solveGraph(String file) throws InterruptedException {
        try {
            graph = loadGraph(file);
            initAdjacencyMatrix();
            x = initModel();
            bestSolution = heuristic();
            startTime = System.currentTimeMillis();
            interruptTime = startTime + (TIME_LIMIT_MINUTE * 60 * 1000);
            BnB();
        } catch (IloException e) {
            e.printStackTrace();
        }
    }

    public static void BnB() throws IloException, InterruptedException {
        if (System.currentTimeMillis() >= interruptTime) {
            throw new InterruptedException();
        }

        cplex.solve();
        double newObjective = cplex.getObjValue();
        double[] newVariables = cplex.getValues(x);
        if (log.isLoggable(Level.INFO)) {
            log.info(String.format("New solution = %f, best solution = %d", newObjective, bestSolution));
        }
        //for some graphs heuristic find max clique size but variables not updated
        if (isWorseSolution(newObjective) && bestVariables != null) {
            if (log.isLoggable(Level.FINE)) {
                log.fine("New solution is worse. Return.");
            }
            return;
        }


        if (isIntegerSolution(newVariables)) {
            if (log.isLoggable(Level.INFO)) {
                log.info(String.format("Found better solution = %f, variables: \n %s", newObjective, Arrays.toString(newVariables)));
            }
            bestSolution = (int) Math.round(newObjective);
            bestVariables = newVariables;
            return;
        }

        int iForBranching = branching(newVariables);
        if (log.isLoggable(Level.FINE)) {
            log.fine(String.format("Branching by %d", iForBranching));
        }
        IloRange UB = addBound(iForBranching, newVariables, true);
        BnB();
        cplex.remove(UB);
        if (log.isLoggable(Level.FINE)) {
            log.fine(String.format("Constraint removed by %s", UB.toString()));
        }
        IloRange LB = addBound(iForBranching, newVariables, false);
        BnB();
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

    public static int heuristic() {
        Cloner cloner = new Cloner();
        Graph graphCopy = cloner.deepClone(graph);
        Set<Node> K = new HashSet<>();
        while (graphCopy.getNodeCount() > 0) {
            int indexOfMaxDegree = StreamSupport.stream(graphCopy.spliterator(), false)
                    .sorted(Comparator.comparingInt(Node::getDegree).reversed())
                    .map(Element::getIndex)
                    .findFirst().orElse(-1);
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
                List<Integer> indexes = independentSet.stream().map(Node::getIndex).collect(Collectors.toList());
                IloNumExpr[] exprs = new IloNumExpr[graph.getNodeCount()];
                for (int j = 0; j < graph.getNodeCount(); j++) {
                    exprs[j] = cplex.prod(xNumVars[j], indexes.contains(j) ? 1 : 0);
                }
                IloRange range = cplex.range(0, cplex.sum(exprs), 1);
                cplex.add(range);
                if (log.isLoggable(Level.INFO)) {
                    log.info(String.format("Added indep set constraints %s", range));
                }
            }
        }

        cplex.add(cplex.le(cplex.sum(xNumVars), maxDegree));

        cplex.add(xNumVars);
        cplex.add(cplex.maximize(cplex.sum(xNumVars)));
        return xNumVars;
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
        byte[][] matrix = adjacencyMatrix.clone();
        while (true) {
            for (Node node : newSet) {
                if (inputNode.getIndex() != node.getIndex()) {
                    matrix[inputNode.getIndex()][node.getIndex()] = 1;
                    matrix[node.getIndex()][inputNode.getIndex()] = 1;
                }
            }
            newSet = getMaximalIndependentSet(matrix, inputNode);
            if (newSet.size() <= 1) {
                break;
            }
            allSets.add(newSet);
        }

        return allSets;
    }

    public static Set<Node> getMaximalIndependentSet(byte[][] matrix, Node inputNode) {
        HashSet<Node> independentNodes = new HashSet<>();
        independentNodes.add(inputNode);
        HashSet<Node> availableNodes = new HashSet<>();
        for (int i = inputNode.getIndex(); i < matrix.length; i++) {
            availableNodes.add(graph.getNode(i));
        }

        HashSet<Node> neighbors = getNeighbors(inputNode, availableNodes, matrix);

        Set<Node> notNeighbors = new HashSet<>(Sets.difference(availableNodes, Sets.union(neighbors, independentNodes)));

        while (notNeighbors.size() > 0) {
            Node node = notNeighbors.iterator().next();
            independentNodes.add(node);
            notNeighbors.removeAll(Sets.union(getNeighbors(node, availableNodes, matrix), independentNodes));
        }

        return independentNodes;
    }

    public static HashSet<Node> getNeighbors(Node inputNode, HashSet<Node> availableNodes, byte[][] matrix) {
        HashSet<Node> neighbors = new HashSet<>();
        for (Node node : availableNodes) {
            if (matrix[node.getIndex()][inputNode.getIndex()] == 1) {
                neighbors.add(node);
            }
        }
        return neighbors;
    }

    public static void initAdjacencyMatrix() {
        int n = graph.getNodeCount();
        adjacencyMatrix = new byte[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                adjacencyMatrix[i][j] = (byte) (graph.getNode(i).hasEdgeBetween(j) ? 1 : 0);
            }
        }
    }

    public static boolean isIntegerSolution(double[] solution) {
        for (double var : solution) {
            long rounded = Math.round(var);
            if (MathUtils.compareTo(rounded, var, EPSI) != 0) {
                return false;
            }
        }
        return true;
    }

    public static boolean isWorseSolution(double newSolution) {
        if (Math.abs(newSolution - Math.round(newSolution)) < EPSI) {
            return MathUtils.compareTo(Math.round(newSolution), bestSolution, EPSI) <= 0;
        }
        return MathUtils.compareTo(Math.floor(newSolution), bestSolution, EPSI) <= 0;
    }
}
