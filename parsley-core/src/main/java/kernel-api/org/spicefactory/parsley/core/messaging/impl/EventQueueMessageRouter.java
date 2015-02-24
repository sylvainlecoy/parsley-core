package org.spicefactory.parsley.core.messaging.impl;

import org.spicefactory.parsley.core.messaging.Message;
import org.spicefactory.parsley.core.messaging.MessageReceiverCache;
import org.spicefactory.parsley.core.messaging.MessageRouter;

public class EventQueueMessageRouter extends DefaultMessageRouter implements MessageRouter {

	@Override
	public void dispatchMessage(Message message, MessageReceiverCache cache) {
		EventQueueMessageProcessor processor = new EventQueueMessageProcessor(message, cache, settings);
		processor.start();
	}

}