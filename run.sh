export CLASSPATH=$PWD:$PWD/lib
make clean
make
#java Cloud 6666 lib/db1.txt c-3333-333 0 30
#java Cloud 6666 lib/db1.txt c-900-333 0 45
#java Cloud 6666 lib/db1.txt c-125-333 0 60
#java Cloud 6666 lib/db1.txt u-100-150-333 0 60
# Above command before code fix
# Stats: {purchased=47, dropped=6, ok=45, timeout=140}
# vm time 716
# with cache Stats: {purchased=97, dropped=22, ok=88, timeout=32}
# Stats: {purchased=87, purchase_after_timeout=1, failed=1, ok=133, timeout=210}
# Stats: {purchased=130, dropped=6, failed=1, ok=191, timeout=118}
#java Cloud 6666 lib/db1.txt c-500-123,10,c-250-123,10,c-200-123,10,c-400-123,10,c-250-123,10 5 50
#java Cloud 6666 lib/db1.txt c-5500-333,20,c-250-333,20,c-5500-333,20 0 60



#java Cloud 6666 lib/db1.txt c-250-333 0 25

#Step up
#java Cloud 6666 lib/db1.txt c-900-123,10,c-555-123,10,c-444-123,10,c-333-123,10,c-133-123,20 5 60
# Stats: {purchased=61, purchase_after_timeout=1, dropped=10, ok=75, timeout=85}
#Step down
#java Cloud 6666 lib/db1.txt c-167-123,15,c-333-123,15,c-444-123,15,c-555-123,10,c-900-123,10 5 65
#java Cloud 6666 lib/db1.txt c-167-123,15,c-333-123,15,c-555-123,15,c-888-123,10,c-1100-123,10 5 65

# Stats: {purchased=58, dropped=1, failed=1, ok=54, timeout=73}
# VM: 507

#java Cloud 6666 lib/db1.txt e-125-333 0 60
java Cloud 6666 lib/db1.txt u-90-180-333 0 60