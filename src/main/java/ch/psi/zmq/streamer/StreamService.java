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

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import javax.inject.Inject;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.glassfish.jersey.media.sse.EventOutput;
import org.glassfish.jersey.media.sse.OutboundEvent;
import org.glassfish.jersey.media.sse.SseBroadcaster;
import org.glassfish.jersey.media.sse.SseFeature;

import com.google.common.eventbus.Subscribe;

import ch.psi.zmq.streamer.model.SendCount;
import ch.psi.zmq.streamer.model.StreamAccounting;
import ch.psi.zmq.streamer.model.StreamInfo;
import ch.psi.zmq.streamer.model.StreamRequest;
import ch.psi.zmq.streamer.model.StreamSource;

@Path("/")
public class StreamService {
	
	private static final Logger logger = Logger.getLogger(StreamService.class.getName());

	@Inject
	private StreamServerConfiguration configuration;
	
	@Inject
	private StreamMap streams;
	
	@Inject
	private SseBroadcaster broadcaster;
	
	@Inject
	private Accountant accountant;
	
	@GET
    @Path("version")
    @Produces(MediaType.TEXT_PLAIN)
    public String getVersion(){
    	String version = getClass().getPackage().getImplementationVersion();
    	if(version==null){
    		version="0.0.0";
    	}
    	return version;
    }
	
	/**
	 * Start streaming for a given tracking id
	 * @param trackingid
	 * @param path
	 * @throws IOException 
	 */
	@PUT
	@Path("stream/{trackingid}")
	public void stream(@PathParam("trackingid") final String trackingid, final StreamRequest request) throws IOException{
		// TODO #frames to take, map header info, path
		
		if(streams.containsKey(trackingid)){
			logger.info(String.format("Stream %s already exists. Stopping existing stream and starting new one.",trackingid));
			Stream s = streams.get(trackingid);
			s.stop();
		}
		
		final Stream stream = new Stream(configuration.getBasedir(), configuration.getNetworkInterface());
		stream.stream(request);
		streams.put(trackingid, stream);
		
		int counter = 0;
		for(StreamSource ss: request.getSource()){
			counter=counter+ss.getNumberOfImages();
		}
		
		final int fcounter = counter;
		// Auto termination
		stream.getStatusBus().register( new Object(){
			@Subscribe
			public void onSend(SendCount status){
				if(fcounter>0 && status.getCount()==fcounter){
					logger.info("Reached image "+status.getCount()+" of "+fcounter+". Stopping streaming");
					terminate(trackingid);
				}
			}
		});
		
		
		
		// Broadcast new stream list
		OutboundEvent.Builder eventBuilder = new OutboundEvent.Builder();
        OutboundEvent event = eventBuilder.name("stream")
            .mediaType(MediaType.APPLICATION_JSON_TYPE)
            .data(List.class, getStreams())
            .build();
        broadcaster.broadcast(event);
	}
	
	@GET
	@Path("stream/{trackingid}")
	public StreamInfo getStreamStatus(@PathParam("trackingid") final String trackingid){
		
		Stream stream = streams.get(trackingid);
		if(stream==null){
			throw new NotFoundException();
		}
		
		StreamInfo info = new StreamInfo();
		info.setStatus(stream.getStatus());
		info.setConfiguration(stream.getConfiguration());
		
		return info;
	}
	
	/**
	 * Terminate the streaming for a given tracking id
	 * @param trackingid
	 */
	@DELETE
	@Path("stream/{trackingid}")
	public StreamInfo terminate(@PathParam("trackingid") String trackingid){
		Stream stream = streams.get(trackingid);
		if(stream==null){
			throw new NotFoundException();
		}
		StreamInfo info = stream.stop();
		streams.remove(trackingid);
		
		// Broadcast new stream list
		OutboundEvent.Builder eventBuilder = new OutboundEvent.Builder();
        OutboundEvent event = eventBuilder.name("stream")
            .mediaType(MediaType.APPLICATION_JSON_TYPE)
            .data(List.class, getStreams())
            .build();
        broadcaster.broadcast(event);
        
        // Add stream info for accounting reasons
        StreamAccounting a = new StreamAccounting();
        a.setTrackingId(trackingid);
        a.setConfiguration(info.getConfiguration());
        a.setStatus(info.getStatus());
        accountant.add(a);
        
        return info;
	}
	
	@GET
	@Path("stream")
	public List<String> getStreams(){
		return(new ArrayList<>(streams.keySet()));
	}
	
	
	@GET
    @Path("events")
    @Produces(SseFeature.SERVER_SENT_EVENTS)
    public EventOutput subscribe() {
        EventOutput eventOutput = new EventOutput();
        broadcaster.add(eventOutput);
        return eventOutput;
    }
	
	
	// RAMDISK Management
	
	@GET
	@Path("basedir/space")
	public long getFreeSpace(){
		return new File(configuration.getBasedir()).getUsableSpace();
	}
	
	@GET
	@Path("basedir/files")
	public List<String> getFiles() throws IOException{
		final List<String> files = new ArrayList<>();
		final String base = configuration.getBasedir();
		
		Files.walkFileTree(new File(base).toPath(), new SimpleFileVisitor<java.nio.file.Path>() {
			@Override
			public FileVisitResult visitFile(java.nio.file.Path file, BasicFileAttributes attrs) throws IOException {
				if (attrs.isRegularFile()) {
		            files.add(file.toString().replaceFirst(base+"/", ""));
		        }
				return FileVisitResult.CONTINUE;
			}
		});
		
		return files;
	}
	
	@DELETE
	@Path("basedir/files")
	public void deleteFiles() throws IOException{
		final String base = configuration.getBasedir();
		final java.nio.file.Path basedir = new File(base).toPath();
		Files.walkFileTree(basedir, new SimpleFileVisitor<java.nio.file.Path>() {

			@Override
			public FileVisitResult visitFile(java.nio.file.Path file, BasicFileAttributes attrs) throws IOException {
				if (attrs.isRegularFile()) {
					logger.info("Deleting file: " + file);
			        Files.delete(file);
		        }
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(java.nio.file.Path dir, IOException exc) throws IOException {
				logger.info("Deleting dir: " + dir);
				if (exc == null) { 
					if(!dir.equals(basedir)){ // keep basedir
						Files.delete(dir);
					}
					return FileVisitResult.CONTINUE;
				} else {
					throw exc;
				}
			}

		});
	}
	
	@GET
	@Path("accounting")
	public List<StreamAccounting> accounting(){
		return(accountant.getInformation());
	}

}
