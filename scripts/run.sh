(cd src && make clean && make && cd ..)
(cd src && export CLASSPATH=$PWD:$PWD/../lib  && java Cloud 1333 ../lib/db1.txt c-714-123 4)