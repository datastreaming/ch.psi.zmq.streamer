/**
 * 
 * Copyright 2013 Paul Scherrer Institute. All rights reserved.
 * 
 * This code is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 * 
 * This code is distributed in the hope that it will be useful, but without any
 * warranty; without even the implied warranty of merchantability or fitness for
 * a particular purpose. See the GNU Lesser General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this code. If not, see <http://www.gnu.org/licenses/>.
 * 
 */
package ch.psi.zmq.streamer;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import ch.psi.zmq.streamer.model.StreamInfo;
import ch.psi.zmq.streamer.model.StreamRequest;
import ch.psi.zmq.streamer.model.StreamSource;
import ch.psi.zmq.streamer.model.StreamStatus;

import com.google.common.eventbus.AsyncEventBus;
import com.google.common.eventbus.EventBus;

/**
 * Streamer to stream out files from a given directory to ZMQ.
 * The streamer is split into a DirectoryWatchDog and a file sender.
 * Each of them are running in a separate thread.
 * 
 */
public class Stream {
	
	private static final Logger logger = Logger.getLogger(Stream.class.getName());
	
	private EventBus bus;
	private List<DirectoryWatchDog> wdogs;
	private ExecutorService wdogExecutor = Executors.newSingleThreadExecutor();
	private FileSender sender;
	private EventBus statusBus;
	
	private StreamRequest configuration;
	
	private final String basedir;
	private final String networkInterface;
	
	public Stream(String basedir, String networkInterface){
		this.basedir = basedir;
		this.networkInterface = networkInterface;
	}
	
	/**
	 * Start streaming data out to ZMQ
	 * @throws IOException 
	 */
	public void stream(final StreamRequest request) throws IOException{
		
		this.configuration = request;
		
		statusBus = new AsyncEventBus(Executors.newSingleThreadExecutor());
		statusBus.register(this);
		
		bus = new AsyncEventBus(Executors.newSingleThreadExecutor());
		
		sender = new FileSender(statusBus, request.getMethod(), networkInterface, request.getPort(), request.getHighWaterMark(), request.isWipeFile());
		
		
		
		logger.info("Start streaming [Options: wipe="+request.isWipeFile()+"] ...");
		sender.setHeader(request.getHeader());
		sender.start();
		bus.register(sender);
		
		wdogs = new ArrayList<>();
		wdogExecutor = Executors.newFixedThreadPool(request.getSource().size());
		for(final StreamSource ssource: request.getSource()){
			final DirectoryWatchDog wdog = new DirectoryWatchDog(bus);
			wdogs.add(wdog);
			
			final Path path = FileSystems.getDefault().getPath(basedir, ssource.getSearchPath());
			// Create directory if it does not exist
			Files.createDirectories(path);
			
			wdogExecutor.execute(new Runnable() {
				@Override
				public void run() {
					try {
						wdog.watch(path, ssource.getSearchPattern(), ssource.getDestinationPath(), ssource.getNumberOfImages());
					} catch (IOException | InterruptedException e) {
						throw new RuntimeException("Unable to start watching path",e);
					}
				}
			});
			
			// Handling of exsiting files
			if(ssource.isStreamExistingFiles()){
				Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
					@Override
					public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
						if (attrs.isRegularFile()) {
							bus.post(new DetectedFile(file, ssource.getDestinationPath()));
				        }
						return FileVisitResult.CONTINUE;
					}
				});
			}
			
		}
	}
	
	
	/**
	 * Stop datastream
	 */
	public StreamInfo stop(){
		logger.info("... terminate streaming");
		
		if(bus!=null && sender!=null && wdogs!=null){
			bus.unregister(sender);
			sender.terminate();
			for(DirectoryWatchDog wdog: wdogs){
				wdog.terminate();
			}
			wdogExecutor.shutdown();
		}
		
		StreamInfo info = new StreamInfo();
		info.setConfiguration(getConfiguration());
		info.setStatus(getStatus());
		
		bus= null;
		sender=null;
		wdogs = null;
		
		logger.info("Streaming terminated");
		return info;
	}
	
	public EventBus getStatusBus(){
		return statusBus;
	}
	
	public StreamStatus getStatus(){
		return new StreamStatus(sender.getMessagesSent());
	}
	
	public StreamRequest getConfiguration(){
		return configuration;
	}
}
