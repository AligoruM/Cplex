import ilog.concert.*;
import ilog.cplex.IloCplex;
import org.apache.commons.math.util.MathUtils;
import org.graphstream.algorithm.coloring.WelshPowell;
import org.graphstream.graph.Graph;
import org.graphstream.graph.Node;
import org.graphstream.graph.implementations.SingleGraph;


import java.io.BufferedReader;
import java.io.FileReader;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

public class CPLEXExample {
    private static final double EPSI = 10e-6;
    private static final int TIME_LIMIT_MINUTE = 5;
    private static String[] testData = {"brock200_2", "C125.9", "C250.9", "keller4"};

    private static IloCplex cplex;
    private static Graph graph;

    private static int bestSolution = 0;
    private static double[] bestVariables;
    private static IloNumVar[] x;
    private static byte[][] adjacencyMatrix;

    private static List<Node> coloredSortedNodes;

    public static void main(String[] args) {
        String testFile = testData[0];
        try {
            long start = System.currentTimeMillis();
            graph = loadGraph(testFile);
            initAdjacencyMatrix();
            x = initModel();
            long end = System.currentTimeMillis();
            System.out.println(String.format("Loading time: %f seconds", (end - start) / 1000F));
            start = System.currentTimeMillis();
            BnB();
            end = System.currentTimeMillis();
            System.out.println(String.format("Solution time: %f seconds", (end - start) / 1000F));
            System.out.println(Arrays.toString(bestVariables));
            System.out.println(String.format("Objective function result of %s data is %d", testFile, bestSolution));
            System.out.println(String.format("Variables %s: \n", Arrays.toString(bestVariables)));
        } catch (IloException e) {
            e.printStackTrace();
        }
    }

    public static void BnB() throws IloException {
        cplex.solve();
        double newObjective = cplex.getObjValue();
        //System.out.println(String.format("New solution = %f, best solution = %d", newObjective, bestSolution));
        if (isWorseSolution(newObjective)) {
            //System.out.println("New solution is worse. Return.");
            return;
        }

        double[] newVariables = cplex.getValues(x);
        //System.out.println(String.format("New vars:\n%s", Arrays.toString(newVariables)));
        if (isIntegerSolution(newVariables)) {
            System.out.println(String.format("Found better solution = %f, variables: \n %s", newObjective, Arrays.toString(newVariables)));
            bestSolution = (int) Math.round(newObjective);
            bestVariables = newVariables;
            return;
        }

        int iForBranching = branching(newVariables);
        //System.out.println(String.format("Branching by %d", iForBranching));
        IloRange UB = addBound(iForBranching, newVariables, true);
        BnB();
        cplex.remove(UB);
        //System.out.println(String.format("Constraint removed by %s", UB.toString()));

        IloRange LB = addBound(iForBranching, newVariables, false);
        BnB();
        cplex.remove(LB);
        //System.out.println(String.format("Constraint removed by %s", LB.toString()));
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
            constraint = cplex.ge(x[branch], ceil);

        } else {
            double floor = Math.floor(newVariables[branch]);
            constraint = cplex.le(x[branch], floor);
        }
        cplex.add(constraint);
        //System.out.println(String.format("Added constraint %s", constraint.toString()));
        return constraint;
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

/*            List<Set<Node>> independentSets = findIndependentSets(graph.getNode(i));
            for (Set<Node> independentSet : independentSets) {
                List<Integer> indexes = independentSet.stream().map(Node::getIndex).collect(Collectors.toList());
                for (Integer index : indexes) {
                    for (Integer integer : indexes) {
                        if(graph.getNode(index).hasEdgeBetween(integer))
                            System.out.println("shiiit");
                    }
                }
                IloNumExpr[] exprs = new IloNumExpr[graph.getNodeCount()];
                for (int j = 0; j < graph.getNodeCount(); j++) {
                    exprs[j]= cplex.prod(xNumVars[j], indexes.contains(j) ? 1 : 0);
                }
                IloRange range = cplex.range(0, cplex.sum(exprs), 1);
                cplex.add(range);
                System.out.println(String.format("Added indep set constraints %s", range));
            }*/
        }

        cplex.add(cplex.le(cplex.sum(xNumVars), maxDegree));

        cplex.add(xNumVars);
        cplex.add(cplex.maximize(cplex.sum(xNumVars)));

        //cplex.setParam(IloCplex.Param.TimeLimit, TIME_LIMIT_MINUTE * 60);

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
        System.out.println(String.format("Chromatic number = %d", color.getChromaticNumber()));
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
        int[] availableNodes = IntStream.range(inputNode.getIndex(), matrix.length).toArray();
        HashSet<Node> independentNodes = new HashSet<>();
        independentNodes.add(inputNode);

        HashSet<Node> notNeighbors = getNeighbors(inputNode, availableNodes, matrix);
        while (notNeighbors.size() > 0) {
            Node node = notNeighbors.iterator().next();
            independentNodes.add(node);
            HashSet<Node> neighbors = getNeighbors(node, availableNodes, matrix);
            neighbors.add(node);
            notNeighbors.removeAll(neighbors);
        }

        return independentNodes;
    }

    public static HashSet<Node> getNeighbors(Node inputNode, int[] availableNodes, byte[][] matrix) {
        HashSet<Node> notNeighbors = new HashSet<>();
        for (int i : availableNodes) {
            if (matrix[i][inputNode.getIndex()] == 0) {
                notNeighbors.add(graph.getNode(i));
            }
        }
        return notNeighbors;
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
        return MathUtils.compareTo(Math.floor(newSolution), bestSolution, EPSI) <= 0;
    }
}
