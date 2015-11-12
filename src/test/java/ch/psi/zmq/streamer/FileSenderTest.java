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

import java.nio.file.FileSystems;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import org.junit.Assert;
import org.junit.Test;

import ch.psi.zmq.streamer.DetectedFile;
import ch.psi.zmq.streamer.FileReceiver;
import ch.psi.zmq.streamer.FileSender;

import com.google.common.eventbus.AsyncEventBus;
import com.google.common.eventbus.EventBus;

public class FileSenderTest {
	
	private static final Logger logger = Logger.getLogger(FileSenderTest.class.getName());
	
	@Test
	public void test() throws InterruptedException {
		
		final EventBus bus = new AsyncEventBus(Executors.newSingleThreadExecutor());
        final FileSender sender = new FileSender(new EventBus(), "push/pull", "*", 9998, 100, false);
        bus.register(sender);
        
        sender.start();
        
        final FileReceiver receiver = new FileReceiver("localhost", 9998, "target");
        Thread t = new Thread(new Runnable() {
			
			@Override
			public void run() {
				receiver.receive(10);
			}
		});
        t.start();

        for(int i=0;i<10;i++){
        	bus.post(new DetectedFile(FileSystems.getDefault().getPath("src/test/resources","testfile.png"), String.format("testfiles/f%d",i)));
        }
        
        Thread.sleep(400); // race condition
        
        sender.terminate();
        
        logger.info("Messages sent: "+sender.getMessagesSent());
        logger.info("Messages received: "+receiver.getMessagesReceived());
        
        // Checks
       	Assert.assertTrue("Messages sent do not correspond to messages received", sender.getMessagesSent() == receiver.getMessagesReceived());
       	
       	
	}

}
