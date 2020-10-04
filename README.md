# Cplex

* Root node processing (before b&c):  
  Real time             =    3.14 sec. (5837.90 ticks)  
Parallel b&c, 6 threads:  
  Real time             =    8.20 sec. (14278.96 ticks)  
  Sync time (average)   =    0.94 sec.  
  Wait time (average)   =    0.16 sec.  
                          ------------  
Total (root+branch&cut) =   11.34 sec. (20116.85 ticks)  
Objective function result of brock200_2 data is 12  

* Root node processing (before b&c):  
  Real time             =    0.33 sec. (281.25 ticks)  
Parallel b&c, 6 threads:
  Real time             =    0.25 sec. (229.48 ticks)  
  Sync time (average)   =    0.08 sec.  
  Wait time (average)   =    0.00 sec.  
                          ------------  
Total (root+branch&cut) =    0.58 sec. (510.73 ticks)  
Objective function result of C125.9 data is 34  

* Root node processing (before b&c):  
  Real time             =    0.70 sec. (742.23 ticks)  
Parallel b&c, 6 threads:
  Real time             =    0.78 sec. (1150.21 ticks)  
  Sync time (average)   =    0.17 sec.  
  Wait time (average)   =    0.00 sec.  
                          ------------  
Total (root+branch&cut) =    1.48 sec. (1892.44 ticks)  
Objective function result of keller4 data is 11  

* Root node processing (before b&c):  
  Real time             =    1.47 sec. (1614.00 ticks)  
Parallel b&c, 6 threads:  
  Real time             =  299.42 sec. (539091.93 ticks)  
  Sync time (average)   =   48.88 sec.  
  Wait time (average)   =    0.86 sec.  
                          ------------  
Total (root+branch&cut) =  300.89 sec. (540705.92 ticks)  
Objective function result of C250.9 data is 44  

С C250.9 сложнее, из-за заметно большего количества данных считалось около 20 минут и GAP снизился только до 13%.
К этому моменту было найдено 8 решений размера 44, что равно оптимальному решению
