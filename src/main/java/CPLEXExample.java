import ilog.concert.IloException;
import ilog.concert.IloIntVar;
import ilog.concert.IloObjective;
import ilog.concert.IloRange;
import ilog.cplex.IloCplex;
import org.graphstream.graph.Graph;
import org.graphstream.graph.implementations.SingleGraph;

import java.io.BufferedReader;
import java.io.FileReader;

/*
--------------------------------------------------------
Root node processing (before b&c):
  Real time             =    3.14 sec. (5837.90 ticks)
Parallel b&c, 6 threads:
  Real time             =    8.20 sec. (14278.96 ticks)
  Sync time (average)   =    0.94 sec.
  Wait time (average)   =    0.16 sec.
                          ------------
Total (root+branch&cut) =   11.34 sec. (20116.85 ticks)
Objective function result of brock200_2 data is 12
--------------------------------------------------------
Root node processing (before b&c):
  Real time             =    0.33 sec. (281.25 ticks)
Parallel b&c, 6 threads:
  Real time             =    0.25 sec. (229.48 ticks)
  Sync time (average)   =    0.08 sec.
  Wait time (average)   =    0.00 sec.
                          ------------
Total (root+branch&cut) =    0.58 sec. (510.73 ticks)
Objective function result of C125.9 data is 34
--------------------------------------------------------
Root node processing (before b&c):
  Real time             =    0.70 sec. (742.23 ticks)
Parallel b&c, 6 threads:
  Real time             =    0.78 sec. (1150.21 ticks)
  Sync time (average)   =    0.17 sec.
  Wait time (average)   =    0.00 sec.
                          ------------
Total (root+branch&cut) =    1.48 sec. (1892.44 ticks)
Objective function result of keller4 data is 11
--------------------------------------------------------
С C250.9 сложнее, из-за заметно большего количества данных считалось около 20 минут и GAP снизился только до 13%.
К этому моменту было найдено 8 решений размера 44, что равно оптимальному решению

Root node processing (before b&c):
  Real time             =    1.47 sec. (1614.00 ticks)
Parallel b&c, 6 threads:
  Real time             =  299.42 sec. (539091.93 ticks)
  Sync time (average)   =   48.88 sec.
  Wait time (average)   =    0.86 sec.
                          ------------
Total (root+branch&cut) =  300.89 sec. (540705.92 ticks)
Objective function result of C250.9 data is 44
 */

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
