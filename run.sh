export CLASSPATH=$PWD:$PWD/lib
make clean
make
#java Cloud 6666 lib/db1.txt c-3333-333 0 30
#java Cloud 6666 lib/db1.txt c-900-333 0 45
java Cloud 6666 lib/db1.txt c-133-333 0 60
# Above command before code fix
# Stats: {purchased=47, dropped=6, ok=45, timeout=140}
# vm time 716
# with cache Stats: {purchased=97, dropped=22, ok=88, timeout=32}
#java Cloud 6666 lib/db1.txt c-500-123,10,c-250-123,10,c-200-123,10,c-400-123,10,c-250-123,10 5 50
#java Cloud 6666 lib/db1.txt c-5500-333,20,c-250-333,20,c-5500-333,20 0 60



#java Cloud 6666 lib/db1.txt c-250-333 0 25

#Step up
#java Cloud 6666 lib/db1.txt c-900-123,10,c-555-123,10,c-444-123,10,c-333-123,10,c-133-123,10 5 50
#Step down
#java Cloud 6666 lib/db1.txt c-167-123,15,c-333-123,15,c-444-123,10,c-555-123,10,c-900-123,10 5 60