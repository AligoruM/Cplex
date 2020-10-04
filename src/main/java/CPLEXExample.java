import ilog.concert.IloException;
import ilog.concert.IloIntVar;
import ilog.concert.IloObjective;
import ilog.concert.IloRange;
import ilog.cplex.IloCplex;
import org.graphstream.graph.Graph;
import org.graphstream.graph.implementations.SingleGraph;

import java.io.BufferedReader;
import java.io.FileReader;

public class CPLEXExample {
    private static final int TIME_LIMIT_MIN = 5;
    private static String[] testData = {"brock200_2", "C125.9", "C250.9", "keller4"};

    public static void main(String[] args) {
        String testFile = testData[2];
        System.setProperty("org.graphstream.ui", "swing"); //хотел визуализировать графы и выделить клику, но плотность ребер не позволяет сделать этого
        try {
            Graph graph = loadGraph(testFile);

            IloCplex cplex = new IloCplex();
            initModel(cplex, graph);

            cplex.setParam(IloCplex.Param.TimeLimit, TIME_LIMIT_MIN * 60);
            cplex.solve();

            cplex.writeSolution("src/main/resources/" + testFile + ".xml");

            System.out.println(String.format("Objective function result of %s data is %d", testFile, (int) cplex.getObjValue()));
        } catch (IloException e) {
            e.printStackTrace();
        }
    }

    public static void initModel(IloCplex cplex, Graph graph) throws IloException {
        IloIntVar[] yBoolVars = cplex.boolVarArray(graph.getNodeCount());

        for (int i = 0; i < graph.getNodeCount(); i++) {
            for (int j = 0; j < graph.getNodeCount(); j++) {
                if (i != j && !graph.getNode(i).hasEdgeBetween(j)) {
                    IloRange lessEqualsRange = cplex.le(cplex.sum(yBoolVars[i], yBoolVars[j]), 1);
                    lessEqualsRange.setName("" + i + " " + j);
                    cplex.add(lessEqualsRange);
                }
            }
        }
        cplex.add(yBoolVars);

        IloObjective maximize = cplex.maximize();
        maximize.setExpr(cplex.sum(yBoolVars));
        cplex.add(maximize);
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
        return graph;
    }
}
