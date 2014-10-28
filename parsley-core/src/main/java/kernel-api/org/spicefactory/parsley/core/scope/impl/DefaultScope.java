package org.spicefactory.parsley.core.scope.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spicefactory.parsley.core.messaging.Message;
import org.spicefactory.parsley.core.messaging.MessageReceiverCache;
import org.spicefactory.parsley.core.messaging.MessageReceiverKind;
import org.spicefactory.parsley.core.messaging.MessageReceiverRegistry;
import org.spicefactory.parsley.core.messaging.MessageRouter;
import org.spicefactory.parsley.core.messaging.impl.DefaultMessage;
import org.spicefactory.parsley.core.scope.Scope;
import org.spicefactory.parsley.core.scope.ScopeInfo;

/**
 * Default implementation of the Scope interface.
 * @author Sylvain Lecoy <sylvain.lecoy@gmail.com>
 */
public class DefaultScope implements Scope {

	private final Logger logger = LoggerFactory.getLogger(DefaultScope.class);

	private final ScopeInfo info;
	private final MessageRouter messageRouter;

	DefaultScope(ScopeInfo info, MessageRouter router) {
		this.info = info;
		this.messageRouter = router;
	}

	@Override
	public String name() {
		return info.name();
	}

	@Override
	public String uuid() {
		return info.uuid();
	}

	@Override
	public boolean inherited() {
		return info.inherited();
	}

	@Override
	public MessageReceiverRegistry messageReceivers() {
		return info.messageReceivers();
	}

	@Override
	public void dispatchMessage(Object instance, String selector) {
		final Class<?> type = instance.getClass();
		final MessageReceiverCache cache = info.getMessageReceiverCache(type);

		if (selector == null) {
			selector = cache.getSelectorValue(instance);
		}
		final Message message = new DefaultMessage(instance, type, selector);

		if (cache.getReceivers(MessageReceiverKind.TARGET, message.selector()).size() == 0) {
			logger.warn("Discarding message '{}' for scope '{}': no matching receiver.", instance.getClass(), name());
			return;
		}

		messageRouter.dispatchMessage(message, cache);
	}
}