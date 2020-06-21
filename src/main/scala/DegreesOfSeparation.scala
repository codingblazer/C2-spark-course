import org.apache.spark._
import org.apache.spark.SparkContext._
import org.apache.spark.rdd._
import org.apache.spark.util.LongAccumulator
import org.apache.log4j._
import scala.collection.mutable.ArrayBuffer

/** Finds the degrees of separation between two Marvel comic book characters, based
 *  on co-appearances in a comic.
 */

/** Problem Statement => You hav 2 files => one containing id and name of superheroes map and other containing connections
 * in different magazines => One magazine = one line in connections file => one line tells all the superheroes that were featured in that magazine and
 * thus they are directly connected to each other i.e. distance = 1
 * In pic 1 => Hulk, thor, ironman connected directly by distance 1 all featuring in same magazine BUT spiderman
 * and hulk appeared in 2nd magazine and thus spiderman and ironman connected by degrees of 2
 * It could have been 3 also i.e. spiderman -> hulk -> thor -> ironman but we take minimum path in BFS manner
 * apply bfs to get minimum separation degree between start and target characters
 */

/** Node = superhero
 * Connection = direct line between 2 nodes tells that these 2 hero appered in same magazine anytime
 * Graph => we take all magazine and draw all connection lines when heroes are related and get graph
 * Source and target will be given
 * Color => white meaning unexplored, grey meaning to be explored in next bfs, black means already explored
 * so we don't explore again and get stuck in recursive
 * Distance initially infinity => distance on node tells min distance of it from source node spiderman S
 * Pic 2,3,4,5 tells how we apply bfs, S being source
 */

/** Implementation details
 * Node => contain the hero id + array of ids to which this is directly connected + distance + color
 * For every line in connections file, we create this node => we have RDD of nodes and the start node is marked gray
 * with distance of 0 in this list of nodes i.e. RDD of nodes
 *
 * Now we do iteration on this RDD again and again which is map function => for every gray node, you find all its connections
 * from conneciton array and in this RDD, update those connection heroes as gray and distance = distance of originating gray node which
 * you just marked black to reach this + 1 and Also, we check if this node we are updating to gray is our target node
 * If it is target node, we update the accumulator from 0 to 1 => this means, before starting any iteration to find gray
 * nodes, we check if the accumulator is non zero. If its non zero, it means we have found min distance to target
 * Also, it might be possible that accumulator has value of more than 1 => i.e. in last iteration, 3-4 gray nodes that we had, more
 * than one of them lead to them but if second gray node was ablt to reach target in less distance, we updated
 * But accumulator incremented twice
 */



object DegreesOfSeparation {
  
  // The characters we want to find the separation between.
  val startCharacterID = 5306 //SpiderMan
  val targetCharacterID = 14 //ADAM 3,031 (who?)
  
  // We make our accumulator a "global" Option so we can reference it in a mapper later.
  var hitCounter:Option[LongAccumulator] = None
  
  // Some custom data types 
  // BFSData contains an array of hero ID connections, the distance, and color.
  type BFSData = (Array[Int], Int, String)
  // A BFSNode has a heroID and the BFSData associated with it.
  type BFSNode = (Int, BFSData)
    
  /** Converts a line of raw input into a BFSNode */
  def convertToBFS(line: String): BFSNode = {
    
    // Split up the line into fields
    val fields = line.split("\\s+")
    
    // Extract this hero ID from the first field
    val heroID = fields(0).toInt
    
    // Extract subsequent hero ID's into the connections array
    var connections: ArrayBuffer[Int] = ArrayBuffer()
    for ( connection <- 1 to (fields.length - 1)) {
      connections += fields(connection).toInt
    }
    
    // Default distance and color is 9999 and white
    var color:String = "WHITE"
    var distance:Int = 9999
    
    // Unless this is the character we're starting from => because in this, we need to have gray so, this will
    //get picked up in first iteration and we can start the process from here further on
    if (heroID == startCharacterID) {
      color = "GRAY"
      distance = 0
    }
    
    return (heroID, (connections.toArray, distance, color))
  }
  
  /** Create "iteration 0" of our RDD of BFSNodes
   * i.e. this will create RDD of nodes which will be used for first iteration
   * */
  def createStartingRdd(sc:SparkContext): RDD[BFSNode] = {
    val inputFile = sc.textFile("../marvel-graph.txt")
    return inputFile.map(convertToBFS)
  }
  
  /** Expands a BFSNode into this node and its children
   * You give a node from your rdd to this and it will return back updated node for next iteration
   * and append constructed children nodes if let say this is a gray node
   * */
  def bfsMap(node:BFSNode): Array[BFSNode] = {
    
    // Extract data from the BFSNode
    val characterID:Int = node._1
    val data:BFSData = node._2
    
    val connections:Array[Int] = data._1
    val distance:Int = data._2
    var color:String = data._3
    
    // This is called from flatMap, so we return an array
    // of potentially many BFSNodes to add to our new RDD
    var results:ArrayBuffer[BFSNode] = ArrayBuffer()
    
    // Gray nodes are flagged for expansion, and create new
    // gray nodes for each connection
    if (color == "GRAY") {
      for (connection <- connections) {
        val newCharacterID = connection
        val newDistance = distance + 1
        val newColor = "GRAY"
        
        // Have we stumbled across the character we're looking for?
        // If so increment our accumulator so the driver script knows.
        if (targetCharacterID == connection) {
          if (hitCounter.isDefined) {
            hitCounter.get.add(1)
          }
        }
        
        // Create our new Gray node for this connection and add it to the results
        val newEntry:BFSNode = (newCharacterID, (Array(), newDistance, newColor))
        results += newEntry
      }
      
      // Color this node as black, indicating it has been processed already.
      color = "BLACK"
    }
    
    // Add the original node back in, so its connections can get merged with 
    // the gray nodes in the reducer.
    val thisEntry:BFSNode = (characterID, (connections, distance, color))
    results += thisEntry
    
    return results.toArray
  }
  
  /** Combine nodes for the same heroID, preserving the shortest length and darkest color.
   * So, after appending the children nodes of gray in previous function, since there can be many gray nodes
   * there can many or none of them leading to target in this very iteration
   * Hence, if more than 2 ways exist, we need to pick the shortest one i.e. recuce same ones into one which is best
   * */
  def bfsReduce(data1:BFSData, data2:BFSData): BFSData = {
    
    // Extract data that we are combining
    val edges1:Array[Int] = data1._1
    val edges2:Array[Int] = data2._1
    val distance1:Int = data1._2
    val distance2:Int = data2._2
    val color1:String = data1._3
    val color2:String = data2._3
    
    // Default node values i.e. this is the node we are going to return and hence updating values in this
    //from the best of 2 that is given and return this
    var distance:Int = 9999
    var color:String = "WHITE"
    var edges:ArrayBuffer[Int] = ArrayBuffer()
    
    // See if one is the original node with its connections.
    // If so preserve them.
    if (edges1.length > 0) {
      edges ++= edges1
    }
    if (edges2.length > 0) {
      edges ++= edges2
    }
    
    // Preserve minimum distance
    if (distance1 < distance) {
      distance = distance1
    }
    if (distance2 < distance) {
      distance = distance2
    }
    
    // Preserve darkest color
    if (color1 == "WHITE" && (color2 == "GRAY" || color2 == "BLACK")) {
      color = color2
    }
    if (color1 == "GRAY" && color2 == "BLACK") {
      color = color2
    }
    if (color2 == "WHITE" && (color1 == "GRAY" || color1 == "BLACK")) {
      color = color1
    }
    if (color2 == "GRAY" && color1 == "BLACK") {
      color = color1
    }
	if (color1 == "GRAY" && color2 == "GRAY") {
	  color = color1
	}
	if (color1 == "BLACK" && color2 == "BLACK") {
	  color = color1
	}
    
    return (edges.toArray, distance, color)
  }
    
  /** Our main function where the action happens */
  def main(args: Array[String]) {
   
    // Set the log level to only print errors
    Logger.getLogger("org").setLevel(Level.ERROR)
    
     // Create a SparkContext using every core of the local machine
    val sc = new SparkContext("local[*]", "DegreesOfSeparation") 
    
    // Our accumulator, used to signal when we find the target 
    // character in our BFS traversal.
    hitCounter = Some(sc.longAccumulator("Hit Counter"))
    
    var iterationRdd = createStartingRdd(sc)
    
    var iteration:Int = 0
    for (iteration <- 1 to 10) {
      println("Running BFS Iteration# " + iteration)
   
      // Create new vertices as needed to darken or reduce distances in the
      // reduce stage. If we encounter the node we're looking for as a GRAY
      // node, increment our accumulator to signal that we're done.

      //THE FLATMAP is used because, iterationRDD is a list and for each of them you are
      //getting a array of bfsNodes => List[Array of bfs node] => flatmap gives list[bfsnodes]
      val mapped: RDD[(Int, (Array[Int], Int, String))] = iterationRdd.flatMap(bfsMap)
      
      // Note that mapped.count() action here forces the RDD to be evaluated, and
      // that's the only reason our accumulator is actually updated.  
      println("Processing " + mapped.count() + " values.")
      
      if (hitCounter.isDefined) {
        val hitCount = hitCounter.get.value
        if (hitCount > 0) {
          println("Hit the target character! From " + hitCount + 
              " different direction(s).")
          return
        }
      }

      //Note that BFS node is not a class or data structure => it is key value pair declared as a type
      //=> key is hero id and when you say reduce by key, they find all the nodes of particular hero
      //in the mapped and reduce them by using the bfsReduce function which takes 2 at a time
      // Reducer combines data for each character ID, preserving the darkest
      // color and shortest path.      
      iterationRdd = mapped.reduceByKey(bfsReduce)
    }
  }
}