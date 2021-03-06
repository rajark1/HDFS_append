package com.hdfs.namenode;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
//import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
//import java.net.InetAddress;
//import java.net.UnknownHostException;

import java.rmi.AlreadyBoundException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
//import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Vector;
import java.util.logging.FileHandler;

import javax.swing.plaf.SliderUI;

import com.google.protobuf.InvalidProtocolBufferException;
import com.hdfs.datanode.FileReaderClass;
import com.hdfs.datanode.FileWriterClass;
import com.hdfs.datanode.IDataNode;
import com.hdfs.miscl.Constants;
import com.hdfs.miscl.Hdfs.AssignBlockRequest;
import com.hdfs.miscl.Hdfs.AssignBlockResponse;
import com.hdfs.miscl.Hdfs.BlockLocationRequest;
import com.hdfs.miscl.Hdfs.BlockLocationResponse;
import com.hdfs.miscl.Hdfs.BlockLocations;
import com.hdfs.miscl.Hdfs.BlockReportRequest;
import com.hdfs.miscl.Hdfs.BlockReportResponse;
import com.hdfs.miscl.Hdfs.CloseFileRequest;
import com.hdfs.miscl.Hdfs.CloseFileResponse;
import com.hdfs.miscl.Hdfs.DataNodeLocation;
import com.hdfs.miscl.Hdfs.HeartBeatRequest;
import com.hdfs.miscl.Hdfs.HeartBeatResponse;
import com.hdfs.miscl.Hdfs.ListFilesRequest;
import com.hdfs.miscl.Hdfs.ListFilesResponse;
import com.hdfs.miscl.Hdfs.OpenFileRequest;
import com.hdfs.miscl.Hdfs.OpenFileResponse;
import com.hdfs.miscl.Hdfs.ReadBlockResponse;
import com.hdfs.miscl.Hdfs.ReadBlockSizeRequest;
import com.hdfs.miscl.Hdfs.ReadBlockSizeResponse;

public class NameNodeDriver implements INameNode
{

	public static HashMap<Integer,DataNodeLocation>  dataNodes;   //data node id, location
	public static HashMap<String,List<DataNodeLocation>> blockLocations;
	public static HashMap<Integer,Long>  heartBeatDataNodes; 
	public static HashMap<String,Integer> allBlocksHashMap;//all blocks that are stored in the HDFS right now
	public static HashMap<String,Integer> activeBlocksHashMap;//all blocks that are either being written or being appended
	public static HashMap<Integer,Vector<String>> handleBlockHashMap;// this will hold only append blocks(old and new), so that needed ones can be removed
	public static PutFile putFile ;
	public static GetFile getFile;
	public static int numBlock=0;
	
	public static void main(String[] args) {

		System.out.println("Hello");
		/**create nnconf file and a directory file has to be created here**/
		
		
		File myFile = new File(Constants.NAME_NODE_CONF_NEW);
		if(myFile.exists()==false)
		{
			try {
				myFile.createNewFile();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
		myFile = new File("File");
		if(myFile.exists()==false)
		{
			myFile.mkdir();
		}
		
		
		
		PopulateBlocks popBlkObj = new PopulateBlocks();
		/**creates the initial map of all block numbers **/
		allBlocksHashMap = popBlkObj.returnAllBlocks();
		
		System.setProperty("java.security.policy","./security.policy");
		//set the security manager
        if (System.getSecurityManager() == null) {
            System.setSecurityManager(new SecurityManager());
        }
		
		dataNodes = new HashMap<>();
		blockLocations = new HashMap<>();
		heartBeatDataNodes = new HashMap<>();
		activeBlocksHashMap = new HashMap<>();
		handleBlockHashMap = new HashMap<>();
		
		putFile = new PutFile();
		getFile = new GetFile();
		
		bindToRegistry();
		checkIfDataNodeIsAlive();
		
	}


	
	static void bindToRegistry()
	{
		System.setProperty("java.rmi.server.hostname",Constants.NAME_NODE_IP);
		NameNodeDriver obj = new NameNodeDriver();
		try {
			
			Registry register=LocateRegistry.createRegistry(Registry.REGISTRY_PORT);
			INameNode stub = (INameNode) UnicastRemoteObject.exportObject(obj,Registry.REGISTRY_PORT);
			try {
				register.bind(Constants.NAME_NODE, stub);
				
				System.out.println("Name Node started succesfully");
			} catch (AlreadyBoundException e) {
				// TODO Auto-generated catch block
				System.out.println("Name Node failed to bind");
				e.printStackTrace();
			}
			
		} catch (RemoteException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}		
	}



/**
 * CloseFile 
 */
	
	public byte[] closeFile(byte[] inp) throws RemoteException {
		// TODO Auto-generated method stub

		CloseFileRequest req = null;
		CloseFileResponse.Builder res = CloseFileResponse.newBuilder();
		res.setStatus(Constants.STATUS_FAILED);
		
		/**here the decision status is as follows
		 * 0 -abort: retain the old number
		 * 1 - commit: send the new number, send old number in the block report
		 * -1 - neither abort nor commit
		 **/
		
		
		try {
			req = CloseFileRequest.parseFrom(inp);
			res.setStatus(Constants.STATUS_SUCCESS);
			
			Integer handle = req.getHandle();
			Integer decision = req.getDecision();
			System.out.println("decision is "+decision);
			
			/**commit the changes, the following need to be performed
			 * 1. discard the old number
			 * 2. update the value to new number
			 * 3. write the new number to the end of the conf file eg 12.1.9 becomes 12.9.9
			 */
			if(decision==1)//commit
			{
				System.out.println("its a commit");
				Vector<String> myBlocks = handleBlockHashMap.get(handle);
				System.out.println("Size if handle" + myBlocks.size());
				
				String oldBlock = myBlocks.get(0);
				String newBlock = myBlocks.get(1);
				
				System.out.println("The old block is "+ oldBlock+" the new block is "+newBlock);
				
				String[] clockOfNewBlock = newBlock.split("\\.");
				
				allBlocksHashMap.remove(oldBlock.split("\\.",0)[0]);
				String[] myArray = newBlock.split("\\.",0);
				allBlocksHashMap.put(myArray[0], Integer.valueOf(myArray[1]));// this reflects the append of the file
				
				/**PERSISTANT CHANGE: update the clock of the last block **/
				String fileName = putFile.fileHandletoFileName.get(handle);
				
				
				
				/**need to send a string of new blocks that will be written into the conf file **/
				updateOnCommit(handleBlockHashMap.get(handle), Constants.PREFIX_DIR+fileName);//newclock is 12.9 changes to 12.9.9
				
				
				/**remove the entry from the block handle hashmap **/
				addBlocksToallBlocksHashMap(handleBlockHashMap.get(handle));
				removeBlocksFromActiveBlocksHashMap(handleBlockHashMap.get(handle)); // handle block contains append blocks with it
				
				
				System.out.println("does activeblock hashmap contain newBlock? "+(activeBlocksHashMap.containsKey(newBlock)));

				/**remove the file handle from the handleHashMap **/
				handleBlockHashMap.remove(handle);
				
				/**now finally dissociate the file handle **/
				
				putFile.fileHandletoFileName.remove(handle);
				putFile.fileBlocks.remove(handle);
				
			}
			else if(decision==0)//abort, so remove the entry from the active block hashmap
			{
				System.out.println("its an abort");

				Vector<String> myBlocks = handleBlockHashMap.get(handle);				
								
				/**remove the entry from the block handle hashmap **/
				removeBlocksFromActiveBlocksHashMap(handleBlockHashMap.get(handle));// handle block contains append blocks with it
//				activeBlocksHashMap.remove(newBlock);
				
				/**remove the file handle from the handleHashMap **/
				handleBlockHashMap.remove(handle);
				
				/**now finally dissociate the file handle **/				
				putFile.fileHandletoFileName.remove(handle);
				putFile.fileBlocks.remove(handle);
			}
			else
			{
				if(handle != null)
				{
					activeBlocksHashMap = putFile.updateActiveBlocks(handle, activeBlocksHashMap);
					allBlocksHashMap = putFile.removeFileHandleNew(handle,allBlocksHashMap);
					
				}
			}
			
		} catch (InvalidProtocolBufferException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return res.build().toByteArray();
	}



	/**
	 * add all the blocks which were appeneded in the all blocks hash map
	 * @param blocks
	 */
	private void addBlocksToallBlocksHashMap(Vector<String> blocks) {
	// TODO Auto-generated method stub
		for(int i=1;i<blocks.size();i++)// from i =1 since 1 = 0 rep
		{
			String[] myArray = blocks.get(i).split("\\.",0);
			allBlocksHashMap.put(myArray[0],Integer.valueOf(myArray[1]));
		}
	}



	@Override
	public byte[] getBlockLocations(byte[] inp) throws RemoteException {
		// TODO Auto-generated method stub
		System.out.println("Block location called");
		
		BlockLocationResponse.Builder res = BlockLocationResponse.newBuilder();
		res.setStatus(Constants.STATUS_FAILED);
		
		try {
			BlockLocationRequest req = BlockLocationRequest.parseFrom(inp);
			
			List<String> blocks = req.getBlockNumsList();
			
			System.out.println("Requested blocks "+blocks);
			
			
			List<BlockLocations> locs  = getFile.getBlockLocations(blocks,blockLocations);
			
			res.setStatus(Constants.STATUS_SUCCESS);
			res.addAllBlockLocations(locs);
			
		
			
		} catch (InvalidProtocolBufferException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		return res.build().toByteArray();
	}

	@Override
	public byte[] assignBlock(byte[] inp) throws RemoteException {
		// TODO Auto-generated method stub
		
		System.out.println("Assign block called");
		AssignBlockResponse.Builder res = AssignBlockResponse.newBuilder();
		res.setStatus(Constants.STATUS_FAILED);
		
		try {
			AssignBlockRequest req = AssignBlockRequest.parseFrom(inp);
			
			int handle = req.getHandle();
			int numBlock = getBlockNum(); //here is the needed change, 12 to 12.1.1
			String newNumBlock = String.valueOf(numBlock);
			/** all blocks under processing are added here **/
			activeBlocksHashMap.put(numBlock+"", 1);//format changed to 12->1, this is for more than 1 block append
			newNumBlock += ".1.1"; //version and clock
			
			putFile.insertFileBlock(handle, newNumBlock);
			
			
			
			res.setStatus(Constants.STATUS_SUCCESS);
			
			BlockLocations.Builder blocks =  BlockLocations.newBuilder();
					
			
			int max = dataNodes.values().size();
			
			System.out.println("Data nodes size "+max);
			
			if(max==1)
			{
				DataNodeLocation value = (DataNodeLocation) dataNodes.values().toArray()[0];
				blocks.addLocations(value);
//				System.out.println("Value is "+value);
			}else
			{
				int [] randoms = getTwoRandoms(max); // get random nodes of all the number of active datanodes
				
				List<Integer> keys      = new ArrayList<Integer>(dataNodes.keySet());
				
				for(int i=0;i<randoms.length;i++)
				{
					Integer randomKey = keys.get( randoms[i]);
					DataNodeLocation value     = dataNodes.get(randomKey);
					blocks.addLocations(value);
				}
				

				
			}
			
	
//			System.out.println("Num block "+numBlock);
			/**check if they are trying to append the block to an existing file**/
			if(req.getIsAppend()==true)
			{				
				handleBlockHashMap.get(handle).add(numBlock+".1");
				System.out.println("the size of the handleblock hash map is "+handleBlockHashMap.size());
			}
			blocks.setBlockNumber(numBlock+".1");//initial version number is 1, therefore 12.1
			res.setNewBlock(blocks);
			
			System.out.println("Response" + res);
			
			return res.build().toByteArray();
			
			
			
		} catch (InvalidProtocolBufferException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		return res.build().toByteArray();
	}



	@Override
	public byte[] list(byte[] inp) throws RemoteException {
		// TODO Auto-generated method stub

		ListFilesResponse.Builder res = ListFilesResponse.newBuilder();
		res.setStatus(Constants.STATUS_FAILED);
		try {
			ListFilesRequest req = ListFilesRequest.parseFrom(inp);
			String wildCard = req.getDirName();
			
			if(wildCard != null)
			{
				List<String> fileNames = getFile.getAllFileNames();
				List<String> matchedFiles = new ArrayList<>();
				for(String str: fileNames)
				{
					if(str.endsWith(wildCard))
					{
						matchedFiles.add(str);
					}
				}
				res.addAllFileNames(matchedFiles);
				
			}else
			{
				res.addAllFileNames(getFile.getAllFileNames());
				
			}
			
			res.setStatus(Constants.STATUS_SUCCESS);
			
		} catch (InvalidProtocolBufferException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		return res.build().toByteArray();
	}



	@SuppressWarnings("null")
	@Override
	public byte[] blockReport(byte[] inp) throws RemoteException {
		// TODO Auto-generated method stub
//		System.out.println("Block report called");
		BlockReportResponse.Builder res = BlockReportResponse.newBuilder();
		
	
		try {
			BlockReportRequest req = BlockReportRequest.parseFrom(inp);
			
			int id = req.getId();
			
			DataNodeLocation loc = req.getLocation();
			
			
			
			dataNodes.put(id,loc);
			
			for(int i=0;i<req.getBlockNumbersCount();i++)
			{
				String numBlock = req.getBlockNumbers(i);
				if(!blockLocations.containsKey(numBlock))//if it doesn't contain key then addd, key and datalocations as the value
				{
					
					List<DataNodeLocation> arrLoc = new ArrayList<DataNodeLocation>();
					arrLoc.add(loc);
					System.out.println("Added block "+numBlock);
					
					blockLocations.put(numBlock, arrLoc);
					
				}else //if the size isn't 2(or whatever the replication factor) yet, then append the datanode location to the value
				{
					List<DataNodeLocation> tmpLoc = blockLocations.get(numBlock);
					
					boolean flag=true;
					for(DataNodeLocation location:tmpLoc)
					{
						if(location.equals(loc))
						{
							flag=false;
							break;
						}
					}
					
					if(flag)
					{
						tmpLoc.add(loc);
					}
				}
				
			}
			
			
			/** now need to use hashmap to  this to send delete blocks **/
			List<String> deleteBlocks = new ArrayList<>();
			List<BlockLocations> compareAndAdd = new ArrayList<>();
			
			for(int i=0;i<req.getBlockNumbersCount();i++)
			{
				String numBlock = req.getBlockNumbers(i);
				/** num block will be present in the form 12.2 **/
				String[] myArray = numBlock.split("\\.");
				String blockNumber = myArray[0];
				Integer version = Integer.valueOf(myArray[1]); 
				/**check if the block is present in the allblockshashMap **/

				if(allBlocksHashMap.get(blockNumber)!=version) //then this maybe an incremented block
				{
					if(activeBlocksHashMap.get(blockNumber)!=version)
					{
						/**need to check first whether the higher version of 
						 * the block is already present with me, in case then delete the block
						 */
						boolean isPresentHigher = checkHigherBlock(blockNumber,loc);
						if(isPresentHigher==true)// means higher version is present and so delete lower version
						{
							
							deleteBlocks.add(numBlock);	
							blockLocations.remove(numBlock);
							
						}
						else // need to send the location of the highest version copy block
						{
							String highestVBlock = blockNumber+".";
							highestVBlock += allBlocksHashMap.get(blockNumber); //will be 12.2
							
							//now get their locations if they are present
							BlockLocations.Builder myBlockLocations = BlockLocations.newBuilder();							
							List<DataNodeLocation> dNodeLocations = blockLocations.get(highestVBlock);
							
							if(dNodeLocations.size()>0)
							{
								myBlockLocations.setBlockNumber(highestVBlock);//case where nodes which contain the latest- blocks aren't up
								myBlockLocations.addAllLocations(dNodeLocations); // added all
								compareAndAdd.add(myBlockLocations.build());
							}
							
							
						}
					}
				}
			}
			
//			System.out.println("delete blocks list is "+deleteBlocks.toString());
			
			res.addAllDeleteBlocks(deleteBlocks);
			res.addAllBlockInfo(compareAndAdd);
			deleteBlocks.clear();			
			compareAndAdd.clear();
			res.addStatus(Constants.STATUS_SUCCESS);
		
		} catch (InvalidProtocolBufferException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		return res.build().toByteArray();

	}

	/**
	 * to check whether to send delete block is sent or copy block is sent
	 * @return
	 */
	boolean checkHigherBlock(String bNumber,DataNodeLocation loc)
	{
		
		Integer highestVersion = allBlocksHashMap.get(bNumber);
		
		List<DataNodeLocation> dataNodeLocations = blockLocations.get(bNumber+"."+highestVersion);
		
		for (Iterator<DataNodeLocation> iterator = dataNodeLocations.iterator();iterator.hasNext();)
		{
			DataNodeLocation dataNodeLocation = (DataNodeLocation) iterator.next();
			System.out.print(dataNodeLocation);
			
			if(dataNodeLocation.equals(loc))
				return true;
			
		}
	
		return false;
	}

	@Override
	public byte[] heartBeat(byte[] inp) throws RemoteException {
		// TODO Auto-generated method stub
		
//	System.out.println("Got heart beat " + new Date().getTime());
		HeartBeatResponse.Builder res  = HeartBeatResponse.newBuilder();
		res.setStatus(Constants.STATUS_SUCCESS);
		try {
			HeartBeatRequest req = HeartBeatRequest.parseFrom(inp);
			heartBeatDataNodes.put(req.getId(), new Date().getTime());
			
		} catch (InvalidProtocolBufferException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		return res.build().toByteArray();
	}
	
	
	
	public static int getRandom(int max)
	{
		int random;
		Random rand =new Random();
		int min=0;
		random = rand.nextInt((max - min) + 1) + min;
		
		return random;
	}
	
	
	public static synchronized int getBlockNum()
	{
		
		
		try {
			
			BufferedReader buff = new BufferedReader(new FileReader(Constants.BLOCK_NUM_FILE));
			String line=buff.readLine();
			buff.close();
			
			Integer num = Integer.parseInt(line);
			num++;
			PrintWriter pw;
			try {
				pw = new PrintWriter(new FileWriter(Constants.BLOCK_NUM_FILE));
			    pw.write(num.toString());
		        pw.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
			return num-1;
			
			
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		return ++numBlock;
	}
	

	/**Need to change it to 3 randoms **/
	private int[] getTwoRandoms(int max) {
		// TODO Auto-generated method stub
		int [] randoms = new int[3];
		Random rand =new Random();
		int min=0;
		
		int random = rand.nextInt((max - min)) + min;
		int random2;
		int random3;
		
		randoms[0]=random;
		
		do
		{
			random2 = rand.nextInt((max - min)) + min;
		}
	    while(random==random2) ;
		
		randoms[1] = random2;
		
		do
		{
			random3 = rand.nextInt((max - min)) + min;
		}
	    while(random3==random2 || random3==random) ;
		
		randoms[2] = random3;
		
		return randoms;
	}
	
	
	private static void checkIfDataNodeIsAlive()
	{
		
		new Thread(new Runnable() {
			
			@Override
			public void run() {
				// TODO Auto-generated method stub
				
			
				
				while(true)
				{
//					System.out.print("hello");
					List<Integer> remove = new ArrayList<>();
					for(Map.Entry<Integer,Long > entry : heartBeatDataNodes.entrySet())
					{
//						System.out.println("current heartbeat datanode "+new Date().getTime() + entry.getValue() + entry.getKey());
						if(new Date().getTime() - entry.getValue() > Constants.HEART_BEAT_FREQ)
						{
							dataNodes.remove(entry.getKey());
							System.out.println("Data node  " + entry.getKey() + " is dead");
							remove.add(entry.getKey());
							
						}
					}
					
					for(Integer key : remove)
					{
						heartBeatDataNodes.remove(key);
					}
					
					
					try {
						Thread.sleep(Constants.HEART_BEAT_FREQ+1000);
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
				
				
			}
		}).start();
		
		
		
	}


	/**
	 * Newly added method, this is to test the new file configuration
	 */
	@Override
	public byte[] openFile(byte[] inp) throws RemoteException {
		// TODO Auto-generated method stub
		System.out.println("Open file new called");
		OpenFileResponse.Builder res = OpenFileResponse.newBuilder();
		res.setStatus(Constants.STATUS_FAILED);
		
		OpenFileRequest req;
		try {
			req = OpenFileRequest.parseFrom(inp);
			
			String fileName = req.getFileName();
			boolean type = req.getForRead();
			boolean isAppend = req.getIsAppend();
			
			if(isAppend)
			{
				byte[] output = appendMethod(inp); 
				return output;
			}
			
			if(!type)   // type = false then write i.e. put
			{
				if(getFile.getFileDetails(fileName)==null)//this means the file isn't present, so we add it
				{
					int fileHandle = new Random().nextInt()%100000;
					fileHandle = Math.abs(fileHandle);
					System.out.println(fileHandle);
				    putFile.insertFileHandle(fileName, fileHandle);
				    
				    res.setHandle(fileHandle);
				    res.setStatus(Constants.STATUS_SUCCESS);
				}
				else
				{
					res.setStatus(Constants.STATUS_NOT_FOUND);
				}
			}
			else       // type = true then read i.e get
			{
				String[] blocks = getFile.getFileDetails(fileName);
				if(blocks==null)
				{
					res.setStatus(Constants.STATUS_NOT_FOUND);
				}else
				{
					res.setStatus(Constants.STATUS_SUCCESS);
					Iterable<String> iterable = Arrays.asList(blocks);
					res.addAllBlockNums(iterable);
				}
				
				
			}
			
			
		} catch (InvalidProtocolBufferException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		
		
		return res.build().toByteArray();

	}
	

	
	public byte[] appendMethod(byte[] inp)
	{
		/**
		 * 1. create a file handle
		 * 2. Increment the clock, save it in the memory and in persistent storage
		 * 3. send the client the new clock number 
		 */
		System.out.println("Append file method called ");
		OpenFileResponse.Builder res = OpenFileResponse.newBuilder();
		res.setStatus(Constants.STATUS_FAILED);
		
		OpenFileRequest req;

		try {
			req = OpenFileRequest.parseFrom(inp);
			String fileName = req.getFileName();
			
			if(getFile.getFileDetails(fileName)==null)//this means the file isn't present, so we add it
			{
				
				res.setStatus(Constants.STATUS_NOT_FOUND);
				
			}
			else
			{
				int fileHandle = new Random().nextInt()%100000;
				fileHandle = Math.abs(fileHandle);
				System.out.println(fileHandle);
			    putFile.insertFileHandle(fileName, fileHandle);
			    
			    /**
			     * Size of last block, last block and new block has to be sent
			     */
			    
			    fileName = Constants.PREFIX_DIR+fileName;
			    String lastBlock = retrieveLastBlock(fileName);
			    
			    /** the received last block is 12.1.2 **/			    
			    String[] myArray = lastBlock.split("\\.");
			    int clock = Integer.parseInt(myArray[myArray.length-1]);//the last index is clock
			    clock = clock +1 ;//increment the clock
			    
			    /** this would give 12.3 **/
			    String newBlock = myArray[0];
			    newBlock = newBlock+".";
			    newBlock = newBlock + String.valueOf(clock);
			    /**add block to active map **/
			    activeBlocksHashMap.put(myArray[0], clock);//changed 12->2 block->version
			    
			    
			    updateClockLastBlock(myArray[0]+"."+myArray[1]+"."+clock,fileName);
			    
			    /** This would give me 12.1**/
			    String oldBlock = myArray[0];
			    oldBlock = oldBlock +".";
			    oldBlock = oldBlock + myArray[1];
			    
			    /**add block to handleBlock hashmap **/
			    Vector<String> blockVersions = new Vector<>();
			    blockVersions.addElement(oldBlock);
			    blockVersions.addElement(newBlock);
			    handleBlockHashMap.put(fileHandle, blockVersions);
			    
			    
			    /**Find the size of the file **/
			    System.out.println("The old block is "+oldBlock);
			    long sizeOfLastBlock = findFileSize(oldBlock);
			    
			    
			    /** set the response**/
			    if(sizeOfLastBlock!=-1)
			    	res.setStatus(Constants.STATUS_SUCCESS);
			    /**handle**/
			    res.setHandle(fileHandle);
			    
			    /**old block only one last block**/
			    String[] blockNums = new String[1]; //only last block is sent			    
			    blockNums[0] = oldBlock;
			    Iterable<String> iterable = Arrays.asList(blockNums);			    
			    res.addAllBlockNums(iterable);
			    
			    /**size of the last block**/
			    res.setSize(sizeOfLastBlock);
			    
			    /**new block **/
			    res.setNewBlockNum(newBlock);
			    
			    
			}
			
			
		} catch (InvalidProtocolBufferException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return res.build().toByteArray();
	}
	
	/**This retrieves the last block of the file **/
	String retrieveLastBlock(String fileName)
	{
		FileReaderClass myFileReader = new FileReaderClass(fileName);
		myFileReader.openFile();
		String prev = "";
		String next = "";
		
		try {
			next = myFileReader.buff_reader.readLine();
			while(next!=null)
			{
				prev = next;
				next = myFileReader.buff_reader.readLine();
			}
			
		myFileReader.closeFile();
			
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		return prev;
	}
	
	/**
	 * This method returns the size of the file	  
	 */
	
	public long findFileSize(String filename) 
	{
		/**find the locations of the datanodes for the given block/filename**/
//		filename = Constants.PREFIX_DIR+filename;
		List<DataNodeLocation> myLocations = blockLocations.get(filename);
		IDataNode dataStub=null;
		
		int dataNodeCounter=0;
		long size = 0;
		DataNodeLocation thisDataNode = null;//dataNodes.get(dataNodeCounter);					
		String ip;// = thisDataNode.getIp();
		int port ; //= thisDataNode.getPort();
		boolean gotDataNodeFlag=false;
		
		do
		{
			try
			{
				thisDataNode = myLocations.get(dataNodeCounter);
				ip = thisDataNode.getIp();
				port = thisDataNode.getPort();
											
				Registry registry2=LocateRegistry.getRegistry(ip,port);					
				dataStub = (IDataNode) registry2.lookup(Constants.DATA_NODE_ID);
				gotDataNodeFlag=true;
			}
			catch (RemoteException e) {
				
				gotDataNodeFlag=false;
				System.out.println("Remote Exception trying to obtain size of last block");
				dataNodeCounter++;
			} catch (NotBoundException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				System.out.println("Trying to obtain last block size");
			} 
		}					
		while(gotDataNodeFlag==false && dataNodeCounter<dataNodes.size());
		
		/**This is an indication to say that even after checking all the datanodes
		 * for that particular block we couldn't get the block since all the nodes were down
		 * so we exit ( we may discuss and change it)
		 */
		if(dataNodeCounter == dataNodes.size())
		{
			System.out.println("All data nodes are down :( ");
			System.out.println("Trying to obtain last block size");
		}
		else
		{
//			dataStub.call shweta's method
			ReadBlockSizeRequest.Builder fileBlockReq = ReadBlockSizeRequest.newBuilder();
			fileBlockReq.setBlockNumber(filename);
			
			try {
				byte[] responseArray = dataStub.readBlockSize(fileBlockReq.build().toByteArray());
				try {
					ReadBlockSizeResponse responseObj = ReadBlockSizeResponse.parseFrom(responseArray);
					
					if(responseObj.getStatus()!=Constants.STATUS_FAILED)
					{
						size = responseObj.getSize();
					}
					
					
				} catch (InvalidProtocolBufferException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			} catch (RemoteException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
		
	    return size;
	}
	
	/**This updates the clock of the last block of the file **/
	public void updateClockLastBlock(String newBlock,String fileName)	
	{
		System.out.println(fileName);
		FileReaderClass myFileReader = new FileReaderClass(fileName);
		myFileReader.openFile();
		
		Vector<String> myString = new Vector<>();
		try
		{
			String line = myFileReader.buff_reader.readLine();
			
			while(line!=null)
			{
				myString.add(line);
				line = myFileReader.buff_reader.readLine();
			}
			
			/** now modify the last line and write into the file again**/
			int lastIndex = myString.size();
			myString.set(lastIndex-1, newBlock);
			
			myFileReader.closeFile();
			
			FileWriterClass myFileWriter = new FileWriterClass(fileName);
			myFileWriter.createFile();
			
			for (String string : myString) {
				myFileWriter.writeline(string);
			}
			
			/**here the last line has now been updated to a newer clock**/			
			myFileWriter.closeFile();
		}
		
		catch(IOException e)
		{
			System.out.println("IOException in Namenode,method is updateClock last block");
		}
			
	}
	
	/**updates the last block of files on commit **/
	public void updateOnCommit(Vector<String> blocks, String fileName)//newclock is 12.9 changes to 12.9.9
	{
		System.out.println(fileName);
		FileReaderClass myFileReader = new FileReaderClass(fileName);
		myFileReader.openFile();
		
		Vector<String> myString = new Vector<>();
		try
		{
			String line = myFileReader.buff_reader.readLine();
			
			while(line!=null)
			{
				myString.add(line);
				line = myFileReader.buff_reader.readLine();
			}
			
			/** now modify the last line and write into the file again**/
			int lastIndex = myString.size();
			
			String newBlock = blocks.get(1);
			String myNewBlock[] = newBlock.split("\\.");
			
			myString.set(lastIndex-1, myNewBlock[0]+"."+myNewBlock[1]+"."+myNewBlock[1]);
			
			/**add remaining blocks and zoom into the configuration file**/
			for(int i=2;i<blocks.size();i++)
			{
				newBlock = blocks.get(i);
				myNewBlock = newBlock.split("\\.");
				myString.add(myNewBlock[0]+"."+myNewBlock[1]+"."+myNewBlock[1]);
			}
			
			myFileReader.closeFile();
			
			FileWriterClass myFileWriter = new FileWriterClass(fileName);
			myFileWriter.createFile();
			
			for (String string : myString) {
				myFileWriter.writeline(string);
			}
			
			/**here the last line has now been updated to a newer clock**/			
			myFileWriter.closeFile();
		}
		
		catch(IOException e)
		{
			System.out.println("IOException in Namenode,method is updateClock last block");
		}

	}
	
	/**
	 *Remove all blocks that were active in the active hashmap 
	 */
	public static void removeBlocksFromActiveBlocksHashMap(Vector<String> blocks)
	{
		for(int i=1;i<blocks.size();i++)// from i =1 since 1 = 0 rep
		{
			System.out.println("removeBlocksActv "+blocks.get(i));
//			String[] myArray = blocks.ge
			activeBlocksHashMap.remove(blocks.get(i).split("\\.",0)[0]); // similar to myArray[0]
		}
	}
}

