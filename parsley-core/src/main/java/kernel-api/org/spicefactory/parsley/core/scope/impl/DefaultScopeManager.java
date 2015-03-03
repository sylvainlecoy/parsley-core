package org.spicefactory.parsley.core.scope.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spicefactory.parsley.core.bootstrap.BootstrapInfo;
import org.spicefactory.parsley.core.command.CommandManager;
import org.spicefactory.parsley.core.command.ObservableCommand;
import org.spicefactory.parsley.core.command.ObservableCommand.CommandObserver;
import org.spicefactory.parsley.core.messaging.Message;
import org.spicefactory.parsley.core.messaging.MessageReceiverCache;
import org.spicefactory.parsley.core.messaging.MessageReceiverKind;
import org.spicefactory.parsley.core.messaging.MessageRouter;
import org.spicefactory.parsley.core.messaging.MessageSettings;
import org.spicefactory.parsley.core.messaging.Selector;
import org.spicefactory.parsley.core.messaging.impl.DefaultMessage;
import org.spicefactory.parsley.core.messaging.receiver.MessageReceiver;
import org.spicefactory.parsley.core.scope.Scope;
import org.spicefactory.parsley.core.scope.ScopeDefinition;
import org.spicefactory.parsley.core.scope.ScopeInfo;
import org.spicefactory.parsley.core.scope.ScopeInfoRegistry;
import org.spicefactory.parsley.core.scope.ScopeManager;

/**
 * Default implementation of the ScopeManager interface.
 * @author Sylvain Lecoy <sylvain.lecoy@gmail.com>
 */
@Singleton
public class DefaultScopeManager implements ScopeManager {

	private final Logger logger = LoggerFactory.getLogger(DefaultScopeManager.class);

	private final Map<String, Scope> scopes;
	private final String defaultReceiverScope;
	private final MessageRouter messageRouter;
	private final CommandManager commandManager;
	private final ScopeInfoRegistry scopeInfoRegistry;

	/////////////////////////////////////////////////////////////////////////////
	// Package-private.
	/////////////////////////////////////////////////////////////////////////////

	@Inject
	DefaultScopeManager(ScopeInfoRegistry scopeInfoRegistry, MessageRouter messageRouter, CommandManager commandManager,
			MessageSettings settings) {
		this.scopes = new HashMap<String, Scope>();
		this.messageRouter = messageRouter;
		this.commandManager = commandManager;
		this.scopeInfoRegistry = scopeInfoRegistry;

		initScopes(null);
		defaultReceiverScope = settings.defaultReceiverScope();
	}

	/**
	 * Creates all scopes managed by this instance.
	 * <p>
	 * The given BootstrapInfo instance provides information about all scopes inherited by parent Contexts and all new scope definitions added
	 * for this Context. The default implementation manages all these scopes and throws an error in case of finding more than one scope with the
	 * same name.
	 * </p>
	 * <p>
	 * This method can be overridden by custom ScopeManagers to allow fine-grained control which scopes should be inherited and/or created.
	 * </p>
	 * @param info a BootstrapInfo instance providing information about all scopes inherited by parent Contexts and all new scope definitions
	 *            added for this Context
	 */
	protected void initScopes(/* TODO: not used. */BootstrapInfo info) {
		for (ScopeInfo scopeInfo : scopeInfoRegistry.getParentScopes()) {
			addScope(scopeInfo, info);
		}
		for (ScopeDefinition scopeDef : scopeInfoRegistry.getNewScopes()) {
			ScopeInfo newScope = new DefaultScopeInfo(scopeDef, commandManager);
			addScope(newScope, info);
		}
	}

	/**
	 * Adds the specified scope to the active scopes of this manager.
	 * @param scopeInfo the scope to add to this manager
	 * @param info the environment of the Context building process
	 */
	protected void addScope(ScopeInfo scopeInfo, /* TODO: not used. */BootstrapInfo info) {
		if (scopes.containsKey(scopeInfo.getName())) {
			throw new IllegalStateException("Duplicate scope with name: " + scopeInfo.getName());
		}
		scopeInfoRegistry.addActiveScope(scopeInfo);
		Scope scope = new DefaultScope(scopeInfo, messageRouter);
		scopes.put(scope.getName(), scope);
	}

	/////////////////////////////////////////////////////////////////////////////
	// Public API.
	/////////////////////////////////////////////////////////////////////////////

	@Override
	public boolean hasScope(String name) {
		return scopes.containsKey(name);
	}

	@Override
	public Scope getScope(@Nullable String name) {
		if (name == null) {
			name = defaultReceiverScope;
		}
		if (!hasScope(name)) {
			throw new IllegalArgumentException("This router does not contain a scope with name " + name);
		}
		return scopes.get(name);
	}

	@Override
	public Collection<Scope> getAllScopes() {
		return scopes.values();
	}

	@Override
	public void dispatchMessage(Object message) {
		dispatchMessage(message, Selector.NONE);
	}

	@Override
	public void dispatchMessage(Object message, Object selector) {
		final Class<?> type = message.getClass();
		final List<ScopeInfo> scopes = scopeInfoRegistry.getActiveScopes();
		final List<MessageReceiverCache> caches = new ArrayList<MessageReceiverCache>(scopes.size());

		for (ScopeInfo scope : scopes) {
			caches.add(scope.getMessageReceiverCache(type));
		}
		final MessageReceiverCache cache = new MergedMessageReceiverCache(caches);

		if (selector.equals(Selector.NONE)) {
			selector = cache.getSelectorValue(message);
		}
		final Message<Object> instance = new DefaultMessage(message, type, selector);

		if (cache.getReceivers(MessageReceiverKind.TARGET, selector).size() == 0) {
			logger.warn("Discarding message '{}': no matching receiver in any scope.", type.getName());
			return;
		}

		messageRouter.dispatchMessage(instance, cache);
	}

	@Override
	public void observeCommand(ObservableCommand command) {
		final List<MessageReceiverCache> typeCaches = new ArrayList<MessageReceiverCache>();
		final List<MessageReceiverCache> triggerCaches = new ArrayList<MessageReceiverCache>();
		for (ScopeInfo scope : scopeInfoRegistry.getActiveScopes()) {
			if (command.getTrigger() != null) {
				triggerCaches.add(scope.getMessageReceiverCache(command.getTrigger().getType()));
			}
			typeCaches.add(scope.getMessageReceiverCache(command.getType()));
			scope.addActiveCommand(command);
		}
		CommandHandler commandHandler = new CommandHandler(typeCaches, triggerCaches);
		command.observe(commandHandler);
		commandHandler.handleCommand(command);
	}

	/////////////////////////////////////////////////////////////////////////////
	// Internal implementation.
	/////////////////////////////////////////////////////////////////////////////

	private class CommandHandler implements CommandObserver {

		private final MessageReceiverCache typeCache;
		private final MessageReceiverCache triggerCache;

		public CommandHandler(List<MessageReceiverCache> typeCaches, List<MessageReceiverCache> triggerCaches) {
			typeCache = new MergedMessageReceiverCache(typeCaches);
			triggerCache = new MergedMessageReceiverCache(triggerCaches);
		}

		@Override
		public void handleCommand(ObservableCommand command) {
			if (!hasReceivers(command)) {
				if (logger.isDebugEnabled()) {
					logger.debug("Discarding command status {} for message '{}': no matching observer.", command.getStatus(),
							(command.getTrigger() == null ? "no trigger" : command.getTrigger().getInstance().getClass()));
				}
				return;
			}

			messageRouter.observeCommand(command, typeCache, triggerCache);
		}

		private boolean hasReceivers(ObservableCommand command) {
			if (command.getTrigger() != null
					&& triggerCache.getReceivers(MessageReceiverKind.forCommandStatus(command.getStatus(), true),
							command.getTrigger().getSelector()).size() > 0) {
				return true;
			}

			return typeCache.getReceivers(MessageReceiverKind.forCommandStatus(command.getStatus(), false), command.getId()).size() > 0;
		}

	}

	private class MergedMessageReceiverCache implements MessageReceiverCache {

		private final List<MessageReceiverCache> caches;

		public MergedMessageReceiverCache(List<MessageReceiverCache> caches) {
			this.caches = caches;
		}

		@Override
		public List<MessageReceiver> getReceivers(MessageReceiverKind kind, Object selector) {
			List<MessageReceiver> receivers = new ArrayList<MessageReceiver>();
			for (MessageReceiverCache cache : caches) {
				receivers.addAll(cache.getReceivers(kind, selector));
			}
			return receivers;
		}

		@Override
		public int getSelectorValue(Object message) {
			return (caches.size() > 0) ? caches.get(0).getSelectorValue(message) : Selector.NONE;
		}

	}

}
