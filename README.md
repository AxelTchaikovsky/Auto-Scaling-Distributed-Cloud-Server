# Auto-Scaling Cloud Server Cluster

## Project Description

This is a 4-tier distributed system providing cloud-hosted web service. The system employs dynamic scaling in response to various loads. The system divides its function into numerous tiers: the front tier receives, accumulates, and passes requests from clients to the next tier; the middle tier processes the requests and retrieves relevant information from the database; and the back-end tier (database) receives, gathers, and passes response to the front tier. Both the front and middle tiers are made up of several servers, and each layer may scale in and out on its own. One master server is in charge of all of the other servers and keeps track of the system’s condition. The system’s scaling method is based on experimental benchmark values.

The system contains a cache tier between the mid-tier servers and the database as a proxy to retain a write-through cache of the database to augment performance and alleviate congestion.

## Running the project

There are many simulated test cases in `run.sh`. 

```
source run.sh
```

## Design

### Master Server Coordination

In the front tier, we first set up a master server (VM 1), and then all of the other servers in the front or middle tier are servers that communicate with the master server. The master server keeps track of all servers’ information, including their server ids and tiers.

Client queries are first processed by a load balancer to distribute to different servers registered as frontend servers. All incoming requests polled by different frontend servers are collected and then send to master’s request queue via RMI, and requests from the queue are polled and processed by the mid-tier servers.

#### Booting handling

Master’s procedure when the first mid-tier sever is still booting. Process at most 15 requests and when process interval is smaller than 800 ms, drop the first request in the serverLib queue. When rate is too high during booting, start 3 middle-tier and 1 front-tier in a row. 

### Scale Out and Scale In Strategies

See  [15_640_P3_Write_Up.pdf](/Users/adam/Library/CloudStorage/OneDrive-Personal/CMU F21/15640/15440-p3/15_640_P3_Write_Up.pdf) 

## Miscellaneous

The main classes (Cloud, ServerLib, Database, ClientSim) and the sample
database file (db1.txt) are in the lib directory.  

A sample server is provided in the sample directory.  To build this, ensure
your CLASSPATH has the lib and sample directory included.  Then run make in 
the sample directory.  

To run the sample, try:
	java Cloud 15440 lib/db1.txt c-2000-1 12
This will launch the "Cloud" service, load the database with the items in 
lib/db1.txt, and start simulating clients arriving at a constant rate every
2000 ms.  A single instance of the sample Server will b erun as a "VM" 
(actually a spearate process).  

See the handout pdf for more details.

