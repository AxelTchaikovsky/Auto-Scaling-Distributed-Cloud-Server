all: ServerInfo.class ServerInterface.class Server.class

%.class: %.java
	javac $<

clean:
	rm -f *.class
