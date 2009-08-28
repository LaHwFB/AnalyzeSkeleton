package skeleton_analysis;

import java.util.ArrayList;



import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.GenericDialog;
import ij.measure.ResultsTable;
import ij.plugin.filter.PlugInFilter;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

/**
 * AnalyzeSkeleton_ plugin for ImageJ(C).
 * Copyright (C) 2008,2009 Ignacio Arganda-Carreras 
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation (http://www.gnu.org/licenses/gpl.txt )
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 * 
 */

/**
 * Main class.
 * This class is a plugin for the ImageJ interface for analyzing
 * 2D/3D skeleton images.
 * <p>
 * For more information, visit the AnalyzeSkeleton_ homepage:
 * http://imagejdocu.tudor.lu/doku.php?id=plugin:analysis:analyzeskeleton:start
 *
 *
 * @version 1.0 08/29/2009
 * @author Ignacio Arganda-Carreras <ignacio.arganda@gmail.com>
 *
 */
public class AnalyzeSkeleton_ implements PlugInFilter
{
	/** end point flag */
	public static byte END_POINT = 30;
	/** junction flag */
	public static byte JUNCTION = 70;
	/** slab flag */
	public static byte SLAB = 127;
	
	/** working image plus */
	private ImagePlus imRef;

	/** working image width */
	private int width = 0;
	/** working image height */
	private int height = 0;
	/** working image depth */
	private int depth = 0;
	/** working image stack*/
	private ImageStack inputImage = null;
	
	/** visit flags */
	private boolean [][][] visited = null;
	
	// Measures
	/** total number of end points voxels */
	private int totalNumberOfEndPoints = 0;
	/** total number of junctions voxels */
	private int totalNumberOfJunctionVoxels = 0;
	/** total number of slab voxels */
	private int totalNumberOfSlabs = 0;	
	
	// Tree fields
	/** number of branches for every specific tree */
	private int[] numberOfBranches = null;
	/** number of end points voxels of every tree */
	private int[] numberOfEndPoints = null;
	/** number of junctions voxels of every tree*/
	private int[] numberOfJunctionVoxels = null;
	/** number of slab voxels of every specific tree */
	private int[] numberOfSlabs = null;	
	/** number of junctions of every specific tree*/
	private int[] numberOfJunctions = null;
	/** number of triple points in every tree */
	private int[] numberOfTriplePoints = null;
	/** list of end points in every tree */
	private ArrayList <Point> endPointsTree [] = null;
	/** list of junction voxels in every tree */
	private ArrayList <Point> junctionVoxelTree [] = null;
	/** list of special slab coordinates where circular tree starts */
	private ArrayList <Point> startingSlabTree [] = null;
	
	/** average branch length */
	private double[] averageBranchLength = null;
	
	/** maximum branch length */
	private double[] maximumBranchLength = null;
	
	/** list of end point coordinates in the entire image */
	private ArrayList <Point> listOfEndPoints = null;
	/** list of junction coordinates in the entire image */
	private ArrayList <Point> listOfJunctionVoxels = null;
	/** list of slab coordinates in the entire image */
	private ArrayList <Point> listOfSlabVoxels = null;
	/** list of slab coordinates in the entire image */
	private ArrayList <Point> listOfStartingSlabVoxels = null;
	
	/** list of groups of junction voxels that belong to the same tree junction (in every tree) */
	private ArrayList < ArrayList <Point> > listOfSingleJunctions[] = null;
	/** array of junction vertex per tree */
	private Vertex[][] junctionVertex = null;
	
	/** stack image containing the corresponding skeleton tags (end point, junction or slab) */
	private ImageStack taggedImage = null;
	
	/** auxiliary temporary point */
	private Point auxPoint = null;
	/** largest branch coordinates initial point */
	private Point[] initialPoint = null;
	/** largest branch coordinates final point */
	private Point[] finalPoint = null;
	
	/** number of trees (skeletons) in the image */
	private int numOfTrees = 0;
	
	/** pruning option */
	private boolean bPruneCycles = true;
	
	/** array of graphs (one per tree) */
	private Graph[] graph = null;
	
	/** auxiliary list of slabs */
	private ArrayList<Point> slabList = null;
	/** auxiliary final vertex */
	private Vertex auxFinalVertex = null;
		
	/** prune cycle options */
	public static final String[] pruneCyclesModes = {"none", "shortest branch"};
	/** prune cycle options index */
	public static int pruneIndex = 0;
	
	private final boolean debug = false;
	
	
	/* -----------------------------------------------------------------------*/
	/**
	 * This method is called once when the filter is loaded.
	 * 
	 * @param arg argument specified for this plugin
	 * @param imp currently active image
	 * @return flag word that specifies the filters capabilities
	 */
	public int setup(String arg, ImagePlus imp) 
	{
		this.imRef = imp;
		
		if (arg.equals("about")) 
		{
			showAbout();
			return DONE;
		}

		return DOES_8G; 
	} /* end setup */
	
	/* -----------------------------------------------------------------------*/
	/**
	 * Process the image: tag skeleton and show results.
	 * 
	 * @see ij.plugin.filter.PlugInFilter#run(ij.process.ImageProcessor)
	 */
	public void run(ImageProcessor ip) 
	{
		GenericDialog gd = new GenericDialog("Analyze Skeleton");
		gd.addChoice("Prune cycle method: ", AnalyzeSkeleton_.pruneCyclesModes, 
										AnalyzeSkeleton_.pruneCyclesModes[pruneIndex]);
		gd.showDialog();
		
		// Exit when canceled
		if (gd.wasCanceled()) 
			return;
		pruneIndex = gd.getNextChoiceIndex();
		
		switch(pruneIndex)
		{
			case 0:
				this.bPruneCycles = false;
				break;
			case 1:
				this.bPruneCycles = true;
				break;
			default:
		}
		
		
		this.width = this.imRef.getWidth();
		this.height = this.imRef.getHeight();
		this.depth = this.imRef.getStackSize();
		this.inputImage = this.imRef.getStack();
		
		// initialize visit flags
		resetVisited();
		
		// Tag skeleton, differentiate trees and visit them				
		processSkeleton(this.inputImage);				
		
		// Prune cycles if necessary
		if(bPruneCycles)
		{
			if(pruneCycles(this.inputImage))
			{
				// initialize visit flags
				resetVisited();
				// Recalculate analysis over the new image
				bPruneCycles = false;
				processSkeleton(this.inputImage);						
			}
			else
				displayTagImage(this.taggedImage);
		}
		
		
		// Calculate triple points (junctions with exactly 3 branches)
		calculateTriplePoints();
		
		if(debug)
			IJ.log("num of skeletons = " + this.numOfTrees);
		
		// Show results table
		showResults();
		
	} // end run method
	
	// ---------------------------------------------------------------------------
	/**
	 * Process skeleton: tag image, mark trees and visit
	 * 
	 * @param inputImage2 input skeleton image to process
	 */
	public void processSkeleton(ImageStack inputImage2) 
	{
		// Initialize  global lists of points
		this.listOfEndPoints = new ArrayList<Point>();
		this.listOfJunctionVoxels = new ArrayList<Point>();
		this.listOfSlabVoxels = new ArrayList<Point>();
		this.listOfStartingSlabVoxels = new ArrayList<Point>();
		this.totalNumberOfEndPoints = 0;
		this.totalNumberOfJunctionVoxels = 0;
		this.totalNumberOfSlabs = 0;
		
		
		// Prepare data: classify voxels and tag them.
		this.taggedImage = tagImage(inputImage2);		
		
		// Show tags image.
		if(!bPruneCycles)
		{
			displayTagImage(taggedImage);
		}
		// Mark trees
		ImageStack treeIS = markTrees(taggedImage);
		
		// Ask memory for every tree
		initializeTrees();
		                                      
		// Divide groups of end-points and junction voxels
		if(this.numOfTrees > 1)
			divideVoxelsByTrees(treeIS);
		else
		{
			if(debug)
				IJ.log("list of end points size = " + this.listOfEndPoints.size());
			this.endPointsTree[0] = this.listOfEndPoints;
			this.numberOfEndPoints[0] = this.listOfEndPoints.size();
			this.junctionVoxelTree[0] = this.listOfJunctionVoxels;
			this.numberOfJunctionVoxels[0] = this.listOfJunctionVoxels.size();
			this.startingSlabTree[0] = this.listOfStartingSlabVoxels;
		}
		
		// Calculate number of junctions (skipping neighbor junction voxels)
		groupJunctions(treeIS);						
		
		// Visit skeleton and measure distances.
		for(int i = 0; i < this.numOfTrees; i++)
			visitSkeleton(taggedImage, treeIS, i+1);
		
	} // end method processSkeleton

	// -----------------------------------------------------------------------
	/**
	 * Prune cycles from tagged image and update it
	 * @param inputImage input image
	 * @return true if the input image was pruned or false if there were no cycles
	 */
	private boolean pruneCycles(ImageStack inputImage) 
	{
		boolean pruned = false;
		
		for(int iTree = 0 ; iTree < this.numOfTrees; iTree ++)
		{
			// For circular trees we just remove one slab
			if(this.startingSlabTree[iTree].size() == 1)
			{
				setPixel(inputImage, this.startingSlabTree[iTree].get(0),(byte) 0);
				pruned = true;
			}
			else // For the rest, we do depth-first search to detect the cycles
			{
				// DFS
				ArrayList <Edge> backEdges = this.graph[iTree].depthFirstSearch();

				if(debug)
				{
					IJ.log( " --------------------------- ");
					final String[] s = new String[]{"UNDEFINED", "TREE" , "BACK"};
					for(final Edge e : this.graph[iTree].getEdges())
					{
						IJ.log(" edge " + e.getV1().getPoints().get(0) + " - " + e.getV2().getPoints().get(0) + " : " + s[e.getType()+1]);
					}
				}

				// If DFS returned backEdges, we need to delete the loops
				if(backEdges.size() > 0)
				{
					// Find all edges of each loop (backtracking the predecessors)
					for(final Edge e : backEdges)
					{
						ArrayList<Edge> loopEdges = new ArrayList<Edge>();
						loopEdges.add(e);

						Edge minEdge = e;

						// backtracking (starting at the vertex with higher order index
						final Vertex finalLoopVertex = e.getV1().getVisitOrder() < e.getV2().getVisitOrder() ? e.getV1() : e.getV2();

						Vertex backtrackVertex = e.getV1().getVisitOrder() < e.getV2().getVisitOrder() ? e.getV2() : e.getV1();

						// backtrack until reaching final loop vertex
						while(!finalLoopVertex.equals(backtrackVertex))
						{
							// Extract predecessor
							final Edge pre = backtrackVertex.getPredecessor();
							// Update shortest loop edge
							if(pre.getSlabs().size() < minEdge.getSlabs().size())
								minEdge = pre;
							// Add to loop edge list
							loopEdges.add(pre);
							// Extract predecessor
							backtrackVertex = pre.getV1().equals(backtrackVertex) ? pre.getV2() : pre.getV1(); 
						}

						// Remove middle slab from the shortest loop edge
						Point removeCoords = minEdge.getSlabs().get(minEdge.getSlabs().size()/2);
						setPixel(inputImage, removeCoords,(byte) 0);
					}

					pruned = true;
				}
			}
		}
		
		return pruned;		
	}// end method pruneCycles

	// -----------------------------------------------------------------------
	/**
	 * Display tag image on a new window
	 * 
	 * @param taggedImage tag image to be diplayed
	 */
	void displayTagImage(ImageStack taggedImage)
	{
		ImagePlus tagIP = new ImagePlus("Tagged skeleton", taggedImage);
		tagIP.show();

		// Set same calibration as the input image
		tagIP.setCalibration(this.imRef.getCalibration());

		// We apply the Fire LUT and reset the min and max to be between 0-255.
		IJ.run(tagIP, "Fire", null);

		//IJ.resetMinAndMax();
		tagIP.resetDisplayRange();
		tagIP.updateAndDraw();
	}
	
	// -----------------------------------------------------------------------
	/**
	 * Divide end point, junction and special (starting) slab voxels in the 
	 * corresponding tree lists
	 * 
	 *  @param treeIS tree image
	 */
	private void divideVoxelsByTrees(ImageStack treeIS) 
	{
		// Add end points to the corresponding tree
		for(int i = 0; i < this.totalNumberOfEndPoints; i++)
		{
			final Point p = this.listOfEndPoints.get(i);
			this.endPointsTree[getShortPixel(treeIS, p) - 1].add(p);
		}
		
		// Add junction voxels to the corresponding tree
		for(int i = 0; i < this.totalNumberOfJunctionVoxels; i++)
		{
			final Point p = this.listOfJunctionVoxels.get(i);			
			this.junctionVoxelTree[getShortPixel(treeIS, p) - 1].add(p);
		}
		
		// Add special slab voxels to the corresponding tree
		for(int i = 0; i < this.listOfStartingSlabVoxels.size(); i++)
		{
			final Point p = this.listOfStartingSlabVoxels.get(i);			
			this.startingSlabTree[getShortPixel(treeIS, p) - 1].add(p);
		}
		
		// Assign number of end points and junction voxels per tree
		for(int iTree = 0; iTree < this.numOfTrees; iTree++)
		{
			this.numberOfEndPoints[iTree] = this.endPointsTree[iTree].size();
			this.numberOfJunctionVoxels[iTree] = this.junctionVoxelTree[iTree].size();
		}
		
	} // end divideVoxelsByTrees

	// -----------------------------------------------------------------------
	/**
	 * Ask memory for trees
	 */
	private void initializeTrees()
	{
		this.numberOfBranches = new int[this.numOfTrees];
		this.numberOfEndPoints = new int[this.numOfTrees];
		this.numberOfJunctionVoxels = new int[this.numOfTrees];
		this.numberOfJunctions = new int[this.numOfTrees];
		this.numberOfSlabs = new int[this.numOfTrees];
		this.numberOfTriplePoints = new int[this.numOfTrees];
		this.averageBranchLength = new double[this.numOfTrees];
		this.maximumBranchLength = new double[this.numOfTrees];
		this.initialPoint = new Point[this.numOfTrees];
		this.finalPoint = new Point[this.numOfTrees];
		this.endPointsTree = new ArrayList[this.numOfTrees];		
		this.junctionVoxelTree = new ArrayList[this.numOfTrees];
		this.startingSlabTree = new ArrayList[this.numOfTrees];
		this.listOfSingleJunctions = new ArrayList[this.numOfTrees];
		
		this.graph = new Graph[this.numOfTrees];
		
		for(int i = 0; i < this.numOfTrees; i++)
		{
			this.endPointsTree[i] = new ArrayList <Point>();
			this.junctionVoxelTree[i] = new ArrayList <Point>();
			this.startingSlabTree[i] = new ArrayList <Point>();
			this.listOfSingleJunctions[i] = new ArrayList < ArrayList <Point> > ();
		}
		this.junctionVertex  = new Vertex[this.numOfTrees][];
	}
	// -----------------------------------------------------------------------
	/**
	 * Show results table
	 */
	private void showResults() 
	{
		ResultsTable rt = new ResultsTable();
		
		String[] head = {"Skeleton", "# Branches","# Junctions", "# End-point voxels",
						 "# Junction voxels","# Slab voxels","Average Branch Length", 
						 "# Triple points", "Maximum Branch Length"};
		
		for (int i = 0; i < head.length; i++)
			rt.setHeading(i,head[i]);	
		
		for(int i = 0 ; i < this.numOfTrees; i++)
		{
			rt.incrementCounter();

			rt.addValue(1, this.numberOfBranches[i]);        
			rt.addValue(2, this.numberOfJunctions[i]);
			rt.addValue(3, this.numberOfEndPoints[i]);
			rt.addValue(4, this.numberOfJunctionVoxels[i]);
			rt.addValue(5, this.numberOfSlabs[i]);
			rt.addValue(6, this.averageBranchLength[i]);
			rt.addValue(7, this.numberOfTriplePoints[i]);
			rt.addValue(8, this.maximumBranchLength[i]);

			if (0 == i % 100) rt.show("Results");
			
			StringBuilder sb = new StringBuilder();

			sb.append("--- Skeleton #").append(i+1).append(" ---\n")
			  	.append("Coordinates of the largest branch:\n")
				.append("Initial point: (").append(this.initialPoint[i].x * this.imRef.getCalibration().pixelWidth).append( ", ") 
				.append(this.initialPoint[i].y * this.imRef.getCalibration().pixelHeight).append( ", ")
				.append(this.initialPoint[i].z * this.imRef.getCalibration().pixelDepth).append(")\n")
				.append("Final point: (").append(this.finalPoint[i].x * this.imRef.getCalibration().pixelWidth).append(", ") 
				.append(this.finalPoint[i].y * this.imRef.getCalibration().pixelHeight).append(", ")
				.append(this.finalPoint[i].z * this.imRef.getCalibration().pixelDepth).append( ")\n" )
				.append("Euclidean distance: ").append( this.calculateDistance(this.initialPoint[i], this.finalPoint[i]));
			IJ.log(sb.toString());
		}
		rt.show("Results");
	}

	/* -----------------------------------------------------------------------*/
	/**
	 * Visit skeleton from end points and register measures.
	 * 
	 * @param taggedImage
	 * 
	 * @deprecated
	 */
	private void visitSkeleton(ImageStack taggedImage) 
	{
	
		// length of branches
		double branchLength = 0;		
		int numberOfBranches = 0;
		double maximumBranchLength = 0;
		double averageBranchLength = 0;
		Point initialPoint = null;
		Point finalPoint = null;
		
	
		// Visit branches starting at end points
		for(int i = 0; i < this.totalNumberOfEndPoints; i++)
		{			
			Point endPointCoord = this.listOfEndPoints.get(i);
					 
			// visit branch until next junction or end point.
			double length = visitBranch(endPointCoord);
						
			if(length == 0)						
				continue;			
			
			// increase number of branches
			numberOfBranches++;
			branchLength += length;				
			
			// update maximum branch length
			if(length > maximumBranchLength)
			{
				maximumBranchLength = length;
				initialPoint = endPointCoord;
				finalPoint = this.auxPoint;
			}
		}
		
	
		// Now visit branches starting at junctions
		for(int i = 0; i < this.totalNumberOfJunctionVoxels; i++)
		{
			Point junctionCoord = this.listOfJunctionVoxels.get(i);
			
			// Mark junction as visited
			setVisited(junctionCoord, true);
					
			Point nextPoint = getNextUnvisitedVoxel(junctionCoord);
			
			while(nextPoint != null)
			{
				branchLength += calculateDistance(junctionCoord, nextPoint);								
								
				double length = visitBranch(nextPoint);
				
				branchLength += length;
				
				// Increase number of branches
				if(length != 0)
				{
					numberOfBranches++;
					// update maximum branch length
					if(length > maximumBranchLength)
					{
						maximumBranchLength = length;
						initialPoint = junctionCoord;
						finalPoint = this.auxPoint;
					}
				}
				
				nextPoint = getNextUnvisitedVoxel(junctionCoord);
			}					
		}

		// Average length
		averageBranchLength = branchLength / numberOfBranches;
		
	} // end visitSkeleton
	
	
	/* -----------------------------------------------------------------------*/
	/**
	 * Visit skeleton from end points and register measures.
	 * 
	 * @param taggedImage
	 * @param treeImage skeleton image with tree classification
	 * @param currentTree number of the tree to be visited
	 */
	private void visitSkeleton(ImageStack taggedImage, ImageStack treeImage, int currentTree) 
	{
		// tree index
		final int iTree = currentTree - 1;
		
		if(debug)
		{
			// Junction vertices in the tree
			IJ.log("this.junctionVertex["+(iTree)+"].length = " + this.junctionVertex[iTree].length);
			for(int i = 0; i < this.junctionVertex[iTree].length; i++)
			{
				IJ.log(" vertices points: " + this.junctionVertex[iTree][i]);
			}
		}
		
		// Create new graph
		this.graph[iTree] = new Graph();
		// Add all junction vertices
		for(int i = 0; i < this.junctionVertex[iTree].length; i++)
			this.graph[iTree].addVertex(this.junctionVertex[iTree][i]);
	
		if(debug)
			IJ.log(" Analyzing tree number " + currentTree);
		// length of branches
		double branchLength = 0;
		
		
		
		this.maximumBranchLength[iTree] = 0;		
		this.numberOfSlabs[iTree] = 0;
	
		// Visit branches starting at end points
		for(int i = 0; i < this.numberOfEndPoints[iTree]; i++)
		{									
			final Point endPointCoord = this.endPointsTree[iTree].get(i);
			
			if(debug)
				IJ.log("\n*** visit from end point: " + endPointCoord + " *** ");
			
			// Skip when visited
			if(isVisited(endPointCoord))
			{
				//if(this.initialPoint[iTree] == null)
				//	IJ.error("WEIRD:" + " (" + endPointCoord.x + ", " + endPointCoord.y + ", " + endPointCoord.z + ")");
				if(debug)
					IJ.log("visited = (" + endPointCoord.x + ", " + endPointCoord.y + ", " + endPointCoord.z + ")");
				continue;
			}
			
			// Initial vertex
			Vertex v1 = new Vertex();
			v1.addPoint(endPointCoord);
			this.graph[iTree].addVertex(v1);
			if(i == 0)
				this.graph[iTree].setRoot(v1);
			
			// slab list for the edge
			this.slabList = new ArrayList<Point>();
					 
			// Otherwise, visit branch until next junction or end point.
			final double length = visitBranch(endPointCoord, iTree);
						
			// If length is 0, it means the tree is formed by only one voxel.
			if(length == 0)
			{
				if(debug)
					IJ.log("set initial point to final point");
				this.initialPoint[iTree] = this.finalPoint[iTree] = endPointCoord;
				continue;
			}
			
			// Add branch to graph
			
			if(debug)
				IJ.log("adding branch from " + v1.getPoints().get(0) + " to " + this.auxFinalVertex.getPoints().get(0));
			this.graph[iTree].addVertex(this.auxFinalVertex);
			this.graph[iTree].addEdge(new Edge(v1, this.auxFinalVertex, this.slabList));
			
			// increase number of branches
			this.numberOfBranches[iTree]++;
			
			if(debug)
				IJ.log("increased number of branches, length = " + length);
			
			branchLength += length;				
			
			// update maximum branch length
			if(length > this.maximumBranchLength[iTree])
			{
				this.maximumBranchLength[iTree] = length;
				this.initialPoint[iTree] = endPointCoord;
				this.finalPoint[iTree] = this.auxPoint;
			}
		}
		
		// If there is no end points, set the first junction as root.
		if(this.numberOfEndPoints[iTree] == 0 && this.junctionVoxelTree[iTree].size() > 0)
			this.graph[iTree].setRoot(this.junctionVertex[iTree][0]);		
	
		if(debug)
			IJ.log( " --------------------------- ");
		
		// Now visit branches starting at junctions
		// 08/26/2009 Changed the loop to visit first the junction voxel that are
		//            forming a single junction.
		for(int i = 0; i < this.junctionVertex[iTree].length; i++)
		{			
			for(int j = 0; j < this.junctionVertex[iTree][i].getPoints().size(); j++)
			{
				final Point junctionCoord = this.junctionVertex[iTree][i].getPoints().get(j);

				if(debug)
					IJ.log("\n*** visit from junction " + junctionCoord + " *** ");

				// Mark junction as visited
				setVisited(junctionCoord, true);

				Point nextPoint = getNextUnvisitedVoxel(junctionCoord);

				while(nextPoint != null)
				{
					// Do not count adjacent junctions
					if( !isJunction(nextPoint))
					{
						// Create graph edge
						this.slabList = new ArrayList<Point>();
						this.slabList.add(nextPoint);

						// Calculate distance from junction to that point
						double length = calculateDistance(junctionCoord, nextPoint);	

						// Visit branch
						this.auxPoint = null;
						length += visitBranch(nextPoint, iTree);

						// Increase total length of branches
						branchLength += length;

						// Increase number of branches
						if(length != 0)
						{				
							if(this.auxPoint == null)
								this.auxPoint = nextPoint;
							
							this.numberOfBranches[iTree]++;
						
							// Initial vertex
							Vertex initialVertex = null;
							for(int k = 0; k < this.junctionVertex[iTree].length; k++)
								if(this.junctionVertex[iTree][k].isVertexPoint(junctionCoord))
								{
									initialVertex = this.junctionVertex[iTree][k];
									break;
								}
							
							
							// If the final point is a slab, then we add the path to the
							// neighbor junction voxel not belonging to the initial vertex
							if(isSlab(this.auxPoint))
							{
								final Point aux = this.auxPoint;
								//IJ.log("Looking for " + this.auxPoint + " in the list of vertices...");
								this.auxPoint = getVisitedJunctionNeighbor(this.auxPoint, initialVertex);
								this.auxFinalVertex = findPointVertex(this.junctionVertex[iTree], this.auxPoint);
								if(this.auxPoint == null)
								{
									IJ.error("Point "+ aux + " has not neighbor end junction!");
								}
								length += calculateDistance(this.auxPoint, aux);
							}
							
							if(debug)
								IJ.log("increased number of branches, length = " + length + " (last point = " + this.auxPoint + ")");
							// update maximum branch length
							if(length > this.maximumBranchLength[iTree])
							{
								this.maximumBranchLength[iTree] = length;
								this.initialPoint[iTree] = junctionCoord;
								this.finalPoint[iTree] = this.auxPoint;
							}

							// Create graph branch
							
							// Add branch to graph
							if(debug)
								IJ.log("adding branch from " + initialVertex.getPoints().get(0) + " to " + this.auxFinalVertex.getPoints().get(0));
							this.graph[iTree].addEdge(new Edge(initialVertex, this.auxFinalVertex, this.slabList));												
						}
					}
					else
						setVisited(nextPoint, true);

					nextPoint = getNextUnvisitedVoxel(junctionCoord);
				}
			}				
		}
		
		if(debug)
			IJ.log( " --------------------------- ");
		
		// Finally visit branches starting at slabs (special case for circular trees)
		if(this.startingSlabTree[iTree].size() == 1)
		{
			if(debug)
				IJ.log("visit from slabs");
			
			final Point startCoord = this.startingSlabTree[iTree].get(0);					
			
			this.slabList = new ArrayList<Point>();
			this.slabList.add(startCoord);
			
			this.numberOfSlabs[iTree]++;
			
			// visit branch until finding visited voxel.
			final double length = visitBranch(startCoord, iTree);
						
			if(length != 0)
			{				
				// increase number of branches
				this.numberOfBranches[iTree]++;
				branchLength += length;				
				
				// update maximum branch length
				if(length > this.maximumBranchLength[iTree])
				{
					this.maximumBranchLength[iTree] = length;
					this.initialPoint[iTree] = startCoord;
					this.finalPoint[iTree] = this.auxPoint;
				}
			}
		}						

		if(debug)
			IJ.log( " --------------------------- ");
		
		if(this.numberOfBranches[iTree] == 0)
			return;
		// Average length
		this.averageBranchLength[iTree] = branchLength / this.numberOfBranches[iTree];
		
		if(debug)
		{
			IJ.log("Num of vertices = " + this.graph[iTree].getVertices().size() + " num of edges = " + this.graph[iTree].getEdges().size());
			for(int i = 0; i < this.graph[iTree].getVertices().size(); i++)
			{
				Vertex v = this.graph[iTree].getVertices().get(i);
				IJ.log(" vertex " + v.getPoints().get(0) + " has neighbors: ");
				for(int j = 0; j < v.getBranches().size(); j++)
				{
					final Vertex v1 = v.getBranches().get(j).getV1();
					final Vertex oppositeVertex = v1.equals(v) ? v.getBranches().get(j).getV2() : v1 ;
					IJ.log(j + ": " + oppositeVertex.getPoints().get(0));
				}

			}

			IJ.log( " --------------------------- ");
			for(int i = 0; i < this.junctionVertex[iTree].length; i ++)
			{
				IJ.log("Junction #" + i + " is formed by: ");
				for(int j = 0; j < this.junctionVertex[iTree][i].getPoints().size(); j++)
					IJ.log(j + ": " + this.junctionVertex[iTree][i].getPoints().get(j));
			}
		}
		
	} // end visitSkeleton
	
	/* -----------------------------------------------------------------------*/
	/**
	 * Color the different trees in the skeleton.
	 * 
	 * @param taggedImage
	 * 
	 * @return image with every tree tagged with a different number 
	 */
	private ImageStack markTrees(ImageStack taggedImage) 
	{
		// Create output image
		ImageStack outputImage = new ImageStack(this.width, this.height, taggedImage.getColorModel());	
		for (int z = 0; z < depth; z++)
		{
			outputImage.addSlice(taggedImage.getSliceLabel(z+1), new ShortProcessor(this.width, this.height));	
		}
	
		this.numOfTrees = 0;
				
		short color = 0;
	
		// Visit trees starting at end points
		for(int i = 0; i < this.totalNumberOfEndPoints; i++)
		{			
			Point endPointCoord = this.listOfEndPoints.get(i);
			
			if(isVisited(endPointCoord))
				continue;
			
			color++;
			
			if(color == Short.MAX_VALUE)
			{
				IJ.error("More than " + (Short.MAX_VALUE-1) +
						" skeletons in the image. AnalyzeSkeleton can only process up to "+ (Short.MAX_VALUE-1));
				return null;
			}
		
			// else, visit the entire tree.
			int numOfVoxelsInTree = visitTree(endPointCoord, outputImage, color);
			
			// increase number of trees			
			this.numOfTrees++;
		}
		
		// Visit trees starting at junction points 
		// (some circular trees do not have end points)
		// Visit trees starting at end points
		for(int i = 0; i < this.totalNumberOfJunctionVoxels; i++)
		{			
			Point junctionCoord = this.listOfJunctionVoxels.get(i);
			if(isVisited(junctionCoord))
				continue;
			
			color++;
			
			if(color == Short.MAX_VALUE)
			{
				IJ.error("More than " + (Short.MAX_VALUE-1) + " skeletons in the image. AnalyzeSkeleton can only process up to 255");
				return null;
			}
		
			// else, visit branch until next junction or end point.
			int length = visitTree(junctionCoord, outputImage, color);
						
			if(length == 0)
			{
				color--; // the color was not used
				continue;
			}
			
			// increase number of trees			
			this.numOfTrees++;
		}
		
		// Check for unvisited slab voxels
		// (just in case there are circular trees without junctions)
		for(int i = 0; i < this.listOfSlabVoxels.size(); i++)
		{
			Point p = (Point) this.listOfSlabVoxels.get(i);
			if(isVisited(p) == false)
			{
				// Mark that voxel as the start point of the circular skeleton
				this.listOfStartingSlabVoxels.add(p);
				
				color++;
				
				if(color == Short.MAX_VALUE)
				{
					IJ.error("More than " + (Short.MAX_VALUE-1) + " skeletons in the image. AnalyzeSkeleton can only process up to 255");
					return null;
				}
			
				// else, visit branch until next junction or end point.
				int length = visitTree(p, outputImage, color);
							
				if(length == 0)
				{
					color--; // the color was not used
					continue;
				}
				
				// increase number of trees			
				this.numOfTrees++;
			}
		}
		
		
		
		//System.out.println("Number of trees = " + this.numOfTrees);

		// Show tree image.
		/*
		ImagePlus treesIP = new ImagePlus("Trees skeleton", outputImage);
		treesIP.show();
		
		// Set same calibration as the input image
		treesIP.setCalibration(this.imRef.getCalibration());
		
		// We apply the Fire LUT and reset the min and max to be between 0-255.
		IJ.run("Fire");
		
		//IJ.resetMinAndMax();
		treesIP.resetDisplayRange();
		treesIP.updateAndDraw();
		*/
		
		// Reset visited variable
		resetVisited();
		
		//IJ.log("Number of trees: " + this.numOfTrees + ", # colors = " + color);
		
		return outputImage;
		
	} /* end markTrees */

	
	/* --------------------------------------------------------------*/
	/**
	 * Visit tree marking the voxels with a reference tree color
	 * 
	 * @param startingPoint starting tree point
	 * @param outputImage 3D image to visit
	 * @param color reference tree color
	 * @return number of voxels in the tree
	 */
	private int visitTree(Point startingPoint, ImageStack outputImage,
			short color) 
	{					
		int numOfVoxels = 0;
		
		//IJ.log("visiting " + startingPoint + " color = " + color);
		
		if(isVisited(startingPoint))	
			return 0;				
		
		// Set pixel color
		this.setPixel(outputImage, startingPoint.x, startingPoint.y, startingPoint.z, color);
		setVisited(startingPoint, true);
		
		ArrayList <Point> toRevisit = new ArrayList <Point>();
		
		Point nextPoint = getNextUnvisitedVoxel(startingPoint);
		
		while(nextPoint != null || toRevisit.size() != 0)
		{
			if(nextPoint != null)
			{
				if(!isVisited(nextPoint))
				{
					numOfVoxels++;
					
					//IJ.log("visiting " + nextPoint+ " color = " + color);
					
					// Set color and visit flat
					this.setPixel(outputImage, nextPoint.x, nextPoint.y, nextPoint.z, color);
					setVisited(nextPoint, true);
					
					// If it is a junction, add it to the revisit list
					if(isJunction(nextPoint))
						toRevisit.add(nextPoint);					
					
					// Calculate next point to visit
					nextPoint = getNextUnvisitedVoxel(nextPoint);
				}				
			}
			else // revisit list
			{				
				nextPoint = toRevisit.get(0);
				//IJ.log("visiting " + nextPoint+ " color = " + color);
												
				// Calculate next point to visit
				nextPoint = getNextUnvisitedVoxel(nextPoint);
				// Maintain junction in the list until there is no more branches
				if (nextPoint == null)
					toRevisit.remove(0);									
			}				
		}
		
		return numOfVoxels;
	} // end method visitTree

	/* -----------------------------------------------------------------------*/
	/**
	 * Visit a branch and calculate length
	 * 
	 * @param startingPoint starting coordinates
	 * @return branch length
	 * 
	 * @deprecated
	 */
	private double visitBranch(Point startingPoint) 
	{
		double length = 0;
		
		// mark starting point as visited
		setVisited(startingPoint, true);
		
		// Get next unvisited voxel
		Point nextPoint = getNextUnvisitedVoxel(startingPoint);
		
		if (nextPoint == null)
			return 0;
		
		Point previousPoint = startingPoint;
		
		// We visit the branch until we find an end point or a junction
		while(nextPoint != null && isSlab(nextPoint))
		{
			// Add length
			length += calculateDistance(previousPoint, nextPoint);
			
			// Mark as visited
			setVisited(nextPoint, true);
			
			// Move in the graph
			previousPoint = nextPoint;			
			nextPoint = getNextUnvisitedVoxel(previousPoint);			
		}
		
		
		if(nextPoint != null)
		{
			// Add distance to last point
			length += calculateDistance(previousPoint, nextPoint);
		
			// Mark last point as visited
			setVisited(nextPoint, true);
		}
		
		this.auxPoint = previousPoint;
		
		return length;
	} /* end visitBranch*/

	/* -----------------------------------------------------------------------*/
	/**
	 * Visit a branch and calculate length in a specifc tree
	 * 
	 * @param startingPoint starting coordinates
	 * @param iTree tree index
	 * @return branch length
	 */
	private double visitBranch(Point startingPoint, int iTree) 
	{
		//IJ.log("startingPoint = (" + startingPoint.x + ", " + startingPoint.y + ", " + startingPoint.z + ")");
		double length = 0;
		
		// mark starting point as visited
		setVisited(startingPoint, true);
		
		// Get next unvisited voxel
		Point nextPoint = getNextUnvisitedVoxel(startingPoint);
		
		if (nextPoint == null)
			return 0;
		
		Point previousPoint = startingPoint;
		
		// We visit the branch until we find an end point or a junction
		while(nextPoint != null && isSlab(nextPoint))
		{			
			this.numberOfSlabs[iTree]++;
		
			// Add slab voxel to the edge
			this.slabList.add(nextPoint);
			
			// Add length
			length += calculateDistance(previousPoint, nextPoint);
			
			// Mark as visited
			setVisited(nextPoint, true);
			
			// Move in the graph
			previousPoint = nextPoint;			
			nextPoint = getNextUnvisitedVoxel(previousPoint);			
		}
		
		
		if(nextPoint != null)
		{
			// Add distance to last point
			length += calculateDistance(previousPoint, nextPoint);
		
			// Mark last point as visited
			setVisited(nextPoint, true);
			
			// Mark final vertex
			if(isEndPoint(nextPoint))
			{
				this.auxFinalVertex = new Vertex();
				this.auxFinalVertex.addPoint(nextPoint);
			}
			else if(isJunction(nextPoint))
			{
				this.auxFinalVertex = findPointVertex(this.junctionVertex[iTree], nextPoint);
				/*
				int j = 0;
				for(j = 0; j < this.junctionVertex[iTree].length; j++)
					if(this.junctionVertex[iTree][j].isVertexPoint(nextPoint))
					{
						this.auxFinalVertex = this.junctionVertex[iTree][j];
						IJ.log(" " + nextPoint + " belongs to junction " + this.auxFinalVertex.getPoints().get(0));
						break;
					}
				if(j == this.junctionVertex[iTree].length)
					IJ.log("point " + nextPoint + " was not found in vertex list!");
					*/
			}
			
			this.auxPoint = nextPoint;
		}
		else
			this.auxPoint = previousPoint;
		
		//IJ.log("finalPoint = (" + nextPoint.x + ", " + nextPoint.y + ", " + nextPoint.z + ")");
		return length;
	} /* end visitBranch*/	
	
	// -----------------------------------------------------------------------
	/**
	 * Find vertex in an array give a vertex point
	 * @param vertex array of search
	 * @param p vertex point
	 * @return vertex containing that point
	 */
	public Vertex findPointVertex(Vertex[] vertex, Point p)
	{
		int j = 0;
		for(j = 0; j < vertex.length; j++)
			if(vertex[j].isVertexPoint(p))
			{		
				if(debug)
					IJ.log(" " + p + " belongs to junction " + vertex[j].getPoints().get(0));
				return vertex[j];
			}
		if(debug)
			IJ.log("point " + p + " was not found in vertex list!");
		return null;
	}
	
	/* -----------------------------------------------------------------------*/
	/**
	 * Calculate distance between two points in 3D
	 * 
	 * @param point1 first point coordinates
	 * @param point2 second point coordinates
	 * @return distance (in the corresponding units)
	 */
	private double calculateDistance(Point point1, Point point2) 
	{		
		return Math.sqrt(  Math.pow( (point1.x - point2.x) * this.imRef.getCalibration().pixelWidth, 2) 
				          + Math.pow( (point1.y - point2.y) * this.imRef.getCalibration().pixelHeight, 2)
				          + Math.pow( (point1.z - point2.z) * this.imRef.getCalibration().pixelDepth, 2));
	}

	/* -----------------------------------------------------------------------*/
	/**
	 * Calculate number of junction skipping neighbor junction voxels
	 * 
	 * @param treeIS tree stack
	 */
	private void groupJunctions(ImageStack treeIS) 
	{
		// Mark all unvisited
		resetVisited();
		
		for (int iTree = 0; iTree < this.numOfTrees; iTree++)
		{
			// Visit list of junction voxels
			for(int i = 0; i < this.numberOfJunctionVoxels[iTree]; i ++)
			{
				Point pi = this.junctionVoxelTree[iTree].get(i);
				
				if(! isVisited(pi))
					fusionNeighborJunction(pi, this.listOfSingleJunctions[iTree]);
			}
		}		
				
		// Count number of single junctions for every tree in the image
		for (int iTree = 0; iTree < this.numOfTrees; iTree++)
		{
			if(debug)
				IJ.log("this.listOfSingleJunctions["+iTree+"].size() = " + this.listOfSingleJunctions[iTree].size());
			
			this.numberOfJunctions[iTree] = this.listOfSingleJunctions[iTree].size();
			
			// Create array of junction vertices for the graph
			this.junctionVertex[iTree] = new Vertex[this.listOfSingleJunctions[iTree].size()];
			
			for(int j = 0 ; j < this.listOfSingleJunctions[iTree].size(); j++)
			{
				final ArrayList<Point> list = this.listOfSingleJunctions[iTree].get(j);
				this.junctionVertex[iTree][j] = new Vertex();
				for(final Point p : list)
					this.junctionVertex[iTree][j].addPoint(p);
				
			}
		}
				
		// Mark all unvisited
		resetVisited();
	}	

	// -----------------------------------------------------------------------
	/**
	 * Reset visit variable and set it to false
	 */
	private void resetVisited()
	{
		// Reset visited variable
		this.visited = null;
		this.visited = new boolean[this.width][this.height][this.depth];
		/*
		for(int i = 0; i < this.width; i ++)
			for(int j = 0; j < this.height; j++)
				for(int k = 0; k < this.depth; k++)
					this.visited[i][j][k] = false;
		*/
	}
	
	// -----------------------------------------------------------------------
	/**
	 * 
	 * @param startingPoint
	 * @param singleJunctionsList
	 */
	private void fusionNeighborJunction(Point startingPoint,
			ArrayList<ArrayList<Point>> singleJunctionsList) 
	{
		// Create new group of junctions
		ArrayList <Point> newGroup = new ArrayList<Point>();
		newGroup.add(startingPoint);
		
		// Mark the starting junction as visited
		setVisited(startingPoint, true);
		
		// Look for neighbor junctions and add them to the new group
		ArrayList <Point> toRevisit = new ArrayList <Point>();
		toRevisit.add(startingPoint);
		
		Point nextPoint = getNextUnvisitedJunctionVoxel(startingPoint);
		
		while(nextPoint != null || toRevisit.size() != 0)
		{
			if(nextPoint != null && !isVisited(nextPoint))
			{			
				// Add to the group
				newGroup.add(nextPoint);
				// Mark as visited
				setVisited(nextPoint, true);

				// add it to the revisit list
				toRevisit.add(nextPoint);

				// Calculate next junction point to visit
				nextPoint = getNextUnvisitedJunctionVoxel(nextPoint);								
			}
			else // revisit list
			{				
				nextPoint = toRevisit.get(0);
				//IJ.log("visiting " + nextPoint + " color = " + color);
												
				// Calculate next point to visit
				nextPoint = getNextUnvisitedJunctionVoxel(nextPoint);
				// Maintain junction in the list until there is no more branches
				if (nextPoint == null)
					toRevisit.remove(0);									
			}				
		}
		
		// Add group to the single junction list
		singleJunctionsList.add(newGroup);
		
	}

	// -----------------------------------------------------------------------
	/**
	 *  Check if two groups of voxels are neighbors
	 * @param g1 first group
	 * @param g2 second group
	 * 
	 * @return true if the groups have any neighbor voxel
	 */
	boolean checkNeighborGroups(ArrayList <Point> g1,  ArrayList <Point> g2)
	{
		for(int i =0 ; i  < g1.size(); i++)
		{
			Point pi = g1.get(i);
			for(int j =0 ; j  < g2.size(); j++)
			{
				Point pj = g2.get(j);
				if(isNeighbor(pi, pj))
					return true;
			}
		}			
		return false;
	}
	
	
	// -----------------------------------------------------------------------
	/**
	 * Calculate number of triple points in the skeleton. Triple points are
	 * junctions with exactly 3 branches.
	 */
	private void calculateTriplePoints() 
	{
		for (int iTree = 0; iTree < this.numOfTrees; iTree++)
		{			
			// Visit the groups of junction voxels
			for(int i = 0; i < this.numberOfJunctions[iTree]; i ++)
			{

				ArrayList <Point> groupOfJunctions = this.listOfSingleJunctions[iTree].get(i);

				// Count the number of slab neighbors of every voxel in the group
				int nSlab = 0;
				for(int j = 0; j < groupOfJunctions.size(); j++)
				{
					Point pj = groupOfJunctions.get(j);

					// Get neighbors and check the slabs
					byte[] neighborhood = this.getNeighborhood(this.taggedImage, pj.x, pj.y, pj.z);
					for(int k = 0; k < 27; k++)
						if (neighborhood[k] == AnalyzeSkeleton_.SLAB)
							nSlab++;
				}
				// If the junction has only 3 slab neighbors, then it is a triple point
				if (nSlab == 3)	
					this.numberOfTriplePoints[iTree] ++;

			}		
				
		}
		
	}// end calculateTriplePoints
	

	/* -----------------------------------------------------------------------*/
	/**
	 * Calculate if two points are neighbors
	 * 
	 * @param point1 first point
	 * @param point2 second point
	 * @return true if the points are neighbors (26-pixel neighborhood)
	 */
	private boolean isNeighbor(Point point1, Point point2) 
	{		
		return Math.sqrt(  Math.pow( (point1.x - point2.x), 2) 
		          + Math.pow( (point1.y - point2.y), 2)
		          + Math.pow( (point1.z - point2.z), 2)) <= Math.sqrt(3);
	}

	/* -----------------------------------------------------------------------*/
	/**
	 * Check if the point is slab
	 *  
	 * @param point actual point
	 * @return true if the point has slab status
	 */
	private boolean isSlab(Point point) 
	{		
		return getPixel(this.taggedImage, point.x, point.y, point.z) == AnalyzeSkeleton_.SLAB;
	}

	/* -----------------------------------------------------------------------*/
	/**
	 * Check if the point is a junction
	 *  
	 * @param point actual point
	 * @return true if the point has slab status
	 */
	private boolean isJunction(Point point) 
	{		
		return getPixel(this.taggedImage, point.x, point.y, point.z) == AnalyzeSkeleton_.JUNCTION;
	}	
	
	/* -----------------------------------------------------------------------*/
	/**
	 * Check if the point is an end point
	 *  
	 * @param point actual point
	 * @return true if the point has slab status
	 */
	private boolean isEndPoint(Point point) 
	{		
		return getPixel(this.taggedImage, point.x, point.y, point.z) == AnalyzeSkeleton_.END_POINT;
	}	
	
	/* -----------------------------------------------------------------------*/
	/**
	 * Check if the point is a junction
	 *  
	 * @param x x- voxel coordinate
	 * @param y y- voxel coordinate
	 * @param z z- voxel coordinate
	 * @return true if the point has slab status
	 */
	private boolean isJunction(int x, int y, int z) 
	{		
		return getPixel(this.taggedImage, x, y, z) == AnalyzeSkeleton_.JUNCTION;
	}	
	
	/* -----------------------------------------------------------------------*/
	/**
	 * Get next unvisited neighbor voxel 
	 * 
	 * @param point starting point
	 * @return unvisited neighbor or null if all neighbors are visited
	 */
	private Point getNextUnvisitedVoxel(Point point) 
	{
		Point unvisitedNeighbor = null;

		// Check neighbors status
		for(int x = -1; x < 2; x++)
			for(int y = -1; y < 2; y++)
				for(int z = -1; z < 2; z++)
				{
					if(x == 0 && y == 0 && z == 0)
						continue;
					
					if(getPixel(this.inputImage, point.x + x, point.y + y, point.z + z) != 0
						&& isVisited(point.x + x, point.y + y, point.z + z) == false)						
					{					
						unvisitedNeighbor = new Point(point.x + x, point.y + y, point.z + z);
						break;
					}
					
				}
		
		return unvisitedNeighbor;
	}/* end getNextUnvisitedVoxel */
	
	/* -----------------------------------------------------------------------*/
	/**
	 * Get next unvisited junction neighbor voxel 
	 * 
	 * @param point starting point
	 * @return unvisited neighbor or null if all neighbors are visited
	 */
	private Point getNextUnvisitedJunctionVoxel(Point point) 
	{
		Point unvisitedNeighbor = null;

		// Check neighbors status
		for(int x = -1; x < 2; x++)
			for(int y = -1; y < 2; y++)
				for(int z = -1; z < 2; z++)
				{
					if(x == 0 && y == 0 && z == 0)
						continue;
					
					if(getPixel(this.inputImage, point.x + x, point.y + y, point.z + z) != 0
						&& isVisited(point.x + x, point.y + y, point.z + z) == false 
						&& isJunction(point.x + x, point.y + y, point.z + z))						
					{					
						unvisitedNeighbor = new Point(point.x + x, point.y + y, point.z + z);
						break;
					}
					
				}
		
		return unvisitedNeighbor;
	}// end getNextUnvisitedJunctionVoxel 

	/* -----------------------------------------------------------------------*/
	/**
	 * Get next visited junction neighbor voxel excluding the ones belonging
	 * to a give vertex
	 * 
	 * @param point starting point
	 * @param exclude exclusion vertex
	 * @return unvisited neighbor or null if all neighbors are visited
	 */
	private Point getVisitedJunctionNeighbor(Point point, Vertex exclude) 
	{
		Point finalNeighbor = null;

		// Check neighbors status
		for(int x = -1; x < 2; x++)
			for(int y = -1; y < 2; y++)
				for(int z = -1; z < 2; z++)
				{
					if(x == 0 && y == 0 && z == 0)
						continue;
					
					final Point neighbor = new Point( point.x + x, point.y + y, point.z + z);
					
					if(getPixel(this.inputImage, neighbor) != 0
						&& isVisited(neighbor)
						&& isJunction(neighbor)
						&& ! exclude.getPoints().contains(neighbor))						
					{					
						finalNeighbor = neighbor;
						break;
					}
					
				}
		
		return finalNeighbor;
	}// end getNextUnvisitedJunctionVoxel 	
	
	/* -----------------------------------------------------------------------*/
	/**
	 * Check if a voxel is visited taking into account the borders. 
	 * Out of range voxels are considered as visited. 
	 * 
	 * @param point
	 * @return true if the voxel is visited
	 */
	private boolean isVisited(Point point) 
	{
		return isVisited(point.x, point.y, point.z);
	}
	
	/* -----------------------------------------------------------------------*/
	/**
	 * Check if a voxel is visited taking into account the borders. 
	 * Out of range voxels are considered as visited. 
	 * 
	 * @param x x- voxel coordinate
	 * @param y y- voxel coordinate
	 * @param z z- voxel coordinate
	 * @return true if the voxel is visited
	 */
	private boolean isVisited(int x, int y, int z) 
	{
		if(x >= 0 && x < this.width && y >= 0 && y < this.height && z >= 0 && z < this.depth)
			return this.visited[x][y][z];
		return true;
	}
	

	/* -----------------------------------------------------------------------*/
	/**
	 * Set value in the visited flags matrix
	 * 
	 * @param x x- voxel coordinate
	 * @param y y- voxel coordinate
	 * @param z z- voxel coordinate
	 * @param b
	 */
	private void setVisited(int x, int y, int z, boolean b) 
	{
		if(x >= 0 && x < this.width && y >= 0 && y < this.height && z >= 0 && z < this.depth)
			this.visited[x][y][z] = b;		
	}

	/* -----------------------------------------------------------------------*/
	/**
	 * Set value in the visited flags matrix
	 * 
	 * @param point voxel coordinates
	 * @param b visited flag value
	 */
	private void setVisited(Point point, boolean b) 
	{
		int x = point.x;
		int y = point.y;
		int z = point.z;
		
 		setVisited(x, y, z, b);	
	}

	/* -----------------------------------------------------------------------*/
	/**
	 * Tag skeleton dividing the voxels between end points, junctions and slab,
	 *  
	 * @param inputImage2 skeleton image to be tagged
	 * @return tagged skeleton image
	 */
	private ImageStack tagImage(ImageStack inputImage2) 
	{
		// Create output image
		ImageStack outputImage = new ImageStack(this.width, this.height, inputImage2.getColorModel());
			
		// Tag voxels
		for (int z = 0; z < depth; z++)
		{
			outputImage.addSlice(inputImage2.getSliceLabel(z+1), new ByteProcessor(this.width, this.height));			
			for (int x = 0; x < width; x++) 
				for (int y = 0; y < height; y++)
				{
					if(getPixel(inputImage2, x, y, z) != 0)
					{
						int numOfNeighbors = getNumberOfNeighbors(inputImage2, x, y, z);
						if(numOfNeighbors < 2)
						{
							setPixel(outputImage, x, y, z, AnalyzeSkeleton_.END_POINT);
							this.totalNumberOfEndPoints++;
							Point endPoint = new Point(x, y, z);
							this.listOfEndPoints.add(endPoint);							
						}
						else if(numOfNeighbors > 2)
						{
							setPixel(outputImage, x, y, z, AnalyzeSkeleton_.JUNCTION);
							Point junction = new Point(x, y, z);
							this.listOfJunctionVoxels.add(junction);	
							this.totalNumberOfJunctionVoxels++;
						}
						else
						{
							setPixel(outputImage, x, y, z, AnalyzeSkeleton_.SLAB);
							Point slab = new Point(x, y, z);
							this.listOfSlabVoxels.add(slab);
							this.totalNumberOfSlabs++;
						}
					}					
				}
		}
		
		return outputImage;
	}/* end tagImage */

	/* -----------------------------------------------------------------------*/
	/**
	 * Get number of neighbors of a voxel in a 3D image (0 border conditions) 
	 * 
	 * @param image 3D image (ImageStack)
	 * @param x x- coordinate
	 * @param y y- coordinate
	 * @param z z- coordinate (in image stacks the indexes start at 1)
	 * @return corresponding 27-pixels neighborhood (0 if out of image)
	 */
	private int getNumberOfNeighbors(ImageStack image, int x, int y, int z)
	{
		int n = 0;
		byte[] neighborhood = getNeighborhood(image, x, y, z);
		
		for(int i = 0; i < 27; i ++)
			if(neighborhood[i] != 0)
				n++;
		// We return n-1 because neighborhood includes the actual voxel.
		return (n-1);			
	}
	
	
	/* -----------------------------------------------------------------------*/
	/**
	 * Get neighborhood of a pixel in a 3D image (0 border conditions) 
	 * 
	 * @param image 3D image (ImageStack)
	 * @param x x- coordinate
	 * @param y y- coordinate
	 * @param z z- coordinate (in image stacks the indexes start at 1)
	 * @return corresponding 27-pixels neighborhood (0 if out of image)
	 */
	private byte[] getNeighborhood(ImageStack image, int x, int y, int z)
	{
		byte[] neighborhood = new byte[27];
		
		neighborhood[ 0] = getPixel(image, x-1, y-1, z-1);
		neighborhood[ 1] = getPixel(image, x  , y-1, z-1);
		neighborhood[ 2] = getPixel(image, x+1, y-1, z-1);
		
		neighborhood[ 3] = getPixel(image, x-1, y,   z-1);
		neighborhood[ 4] = getPixel(image, x,   y,   z-1);
		neighborhood[ 5] = getPixel(image, x+1, y,   z-1);
		
		neighborhood[ 6] = getPixel(image, x-1, y+1, z-1);
		neighborhood[ 7] = getPixel(image, x,   y+1, z-1);
		neighborhood[ 8] = getPixel(image, x+1, y+1, z-1);
		
		neighborhood[ 9] = getPixel(image, x-1, y-1, z  );
		neighborhood[10] = getPixel(image, x,   y-1, z  );
		neighborhood[11] = getPixel(image, x+1, y-1, z  );
		
		neighborhood[12] = getPixel(image, x-1, y,   z  );
		neighborhood[13] = getPixel(image, x,   y,   z  );
		neighborhood[14] = getPixel(image, x+1, y,   z  );
		
		neighborhood[15] = getPixel(image, x-1, y+1, z  );
		neighborhood[16] = getPixel(image, x,   y+1, z  );
		neighborhood[17] = getPixel(image, x+1, y+1, z  );
		
		neighborhood[18] = getPixel(image, x-1, y-1, z+1);
		neighborhood[19] = getPixel(image, x,   y-1, z+1);
		neighborhood[20] = getPixel(image, x+1, y-1, z+1);
		
		neighborhood[21] = getPixel(image, x-1, y,   z+1);
		neighborhood[22] = getPixel(image, x,   y,   z+1);
		neighborhood[23] = getPixel(image, x+1, y,   z+1);
		
		neighborhood[24] = getPixel(image, x-1, y+1, z+1);
		neighborhood[25] = getPixel(image, x,   y+1, z+1);
		neighborhood[26] = getPixel(image, x+1, y+1, z+1);
		
		return neighborhood;
	} /* end getNeighborhood */
	
	/* -----------------------------------------------------------------------*/
	/**
	 * Get pixel in 3D image (0 border conditions) 
	 * 
	 * @param image 3D image
	 * @param x x- coordinate
	 * @param y y- coordinate
	 * @param z z- coordinate (in image stacks the indexes start at 1)
	 * @return corresponding pixel (0 if out of image)
	 */
	private byte getPixel(ImageStack image, int x, int y, int z)
	{
		if(x >= 0 && x < this.width && y >= 0 && y < this.height && z >= 0 && z < this.depth)
			return ((byte[]) image.getPixels(z + 1))[x + y * this.width];
		else return 0;
	} /* end getPixel */
	
	
	/* -----------------------------------------------------------------------*/
	/**
	 * Get pixel in 3D image (0 border conditions) 
	 * 
	 * @param image 3D image
	 * @param x x- coordinate
	 * @param y y- coordinate
	 * @param z z- coordinate (in image stacks the indexes start at 1)
	 * @return corresponding pixel (0 if out of image)
	 */
	private short getShortPixel(ImageStack image, int x, int y, int z)
	{
		if(x >= 0 && x < this.width && y >= 0 && y < this.height && z >= 0 && z < this.depth)
			return ((short[]) image.getPixels(z + 1))[x + y * this.width];
		else return 0;
	} /* end getShortPixel */

	/* -----------------------------------------------------------------------*/
	/**
	 * Get pixel in 3D image (0 border conditions) 
	 * 
	 * @param image 3D image
	 * @param point point to be evaluated
	 * @return corresponding pixel (0 if out of image)
	 */
	private short getShortPixel(ImageStack image, Point point)
	{
		return getShortPixel(image, point.x, point.y, point.z);
	} // end getPixel 
	
	/* -----------------------------------------------------------------------*/
	/**
	 * Get pixel in 3D image (0 border conditions) 
	 * 
	 * @param image 3D image
	 * @param point point to be evaluated
	 * @return corresponding pixel (0 if out of image)
	 */
	private byte getPixel(ImageStack image, Point point)
	{
		return getPixel(image, point.x, point.y, point.z);
	} // end getPixel 
	
	/* -----------------------------------------------------------------------*/
	/**
	 * Set pixel in 3D image 
	 * 
	 * @param image 3D image
	 * @param p point coordinates
	 * @param value pixel value
	 */
	private void setPixel(ImageStack image, Point p, byte value)
	{
		if(p.x >= 0 && p.x < this.width && p.y >= 0 && p.y < this.height && p.z >= 0 && p.z < this.depth)
			((byte[]) image.getPixels(p.z + 1))[p.x + p.y * this.width] = value;
	} // end setPixel 
	
	/* -----------------------------------------------------------------------*/
	/**
	 * Set pixel in 3D image 
	 * 
	 * @param image 3D image
	 * @param x x- coordinate
	 * @param y y- coordinate
	 * @param z z- coordinate (in image stacks the indexes start at 1)
	 * @param value pixel value
	 */
	private void setPixel(ImageStack image, int x, int y, int z, byte value)
	{
		if(x >= 0 && x < this.width && y >= 0 && y < this.height && z >= 0 && z < this.depth)
			((byte[]) image.getPixels(z + 1))[x + y * this.width] = value;
	} // end setPixel 

	/* -----------------------------------------------------------------------*/
	/**
	 * Set pixel in 3D (short) image 
	 * 
	 * @param image 3D image
	 * @param x x- coordinate
	 * @param y y- coordinate
	 * @param z z- coordinate (in image stacks the indexes start at 1)
	 * @param value pixel value
	 */
	private void setPixel(ImageStack image, int x, int y, int z, short value)
	{
		if(x >= 0 && x < this.width && y >= 0 && y < this.height && z >= 0 && z < this.depth)
			((short[]) image.getPixels(z + 1))[x + y * this.width] = value;
	} // end setPixel 	
	

	
	/* -----------------------------------------------------------------------*/
	/**
	 * Show plug-in information.
	 * 
	 */
	void showAbout() 
	{
		IJ.showMessage(
						"About AnalyzeSkeleton...",
						"This plug-in filter analyzes a 2D/3D image skeleton.\n");
	} // end showAbout 
	


}
