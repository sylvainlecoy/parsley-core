package org.spicefactory.parsley.command.impl;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.spicefactory.lib.command.CommandResult;
import org.spicefactory.lib.command.data.CommandData;
import org.spicefactory.lib.command.lifecycle.DefaultCommandLifecycle;
import org.spicefactory.lib.command.proxy.CommandProxy;
import org.spicefactory.parsley.core.command.CommandStatus;
import org.spicefactory.parsley.core.command.ManagedCommandProxy;
import org.spicefactory.parsley.core.command.ObservableCommand;
import org.spicefactory.parsley.core.context.Context;
import org.spicefactory.parsley.core.messaging.Message;

/**
 * Extension of the stand-alone CommandLifecycle from Spicelib that deals with adding and removing commands to/from the Context during execution
 * and passing them to the CommandManager.
 * @author Sylvain Lecoy <sylvain.lecoy@swissquote.ch>
 */
public class ManagedCommandLifecycle extends DefaultCommandLifecycle {

	private final Context context;
	private final Message<Object> trigger;
	private final Map<Object, DefaultObservableCommand> observables;

	private boolean root = true;
	private int nextId;

	/////////////////////////////////////////////////////////////////////////////
	// Component exposed API.
	/////////////////////////////////////////////////////////////////////////////

	public ManagedCommandLifecycle(Context context, ManagedCommandProxy root) {
		this(context, root, null);
	}

	public ManagedCommandLifecycle(Context context, ManagedCommandProxy root, @Nullable Message<Object> trigger) {
		this.context = context;
		this.trigger = trigger;
		this.nextId = root.getID();
		this.observables = new HashMap<Object, DefaultObservableCommand>();
	}

	/////////////////////////////////////////////////////////////////////////////
	// Public API.
	/////////////////////////////////////////////////////////////////////////////

	@Override
	public <T> T createInstance(Class<T> type, CommandData data) {
		T instance = super.createInstance(type, data);
		context.injectMembers(instance);
		return instance;
	}

	@Override
	public void beforeExecution(Object command, CommandData data) {
		context.injectMembers(command);
		if (isObservableTarget(command)) {
			context.getScopeManager().observeCommand(createObservableCommand(command));
		} else if (command instanceof ManagedCommandProxy) {
			nextId = ((ManagedCommandProxy) command).getID();
		}
	}

	@Override
	public void afterCompletion(Object command, CommandResult result) {
		if (observables.containsKey(command)) {
			observables.get(command).setResult(result);
		}
	}

	/////////////////////////////////////////////////////////////////////////////
	// Internal implementation.
	/////////////////////////////////////////////////////////////////////////////

	private boolean isObservableTarget(Object command) {
		return !(command instanceof CommandProxy);
	}

	private ObservableCommand createObservableCommand(Object command) {
		DefaultObservableCommand observable = new DefaultObservableCommand(command, command.getClass(), String.valueOf(nextId), root);
		root = false;
		nextId = -1;
		observables.put(command, observable);
		return observable;
	}

	private class DefaultObservableCommand implements ObservableCommand {

		private final Object command;
		private final Class<?> type;
		private final boolean root;
		private final String id;
		private final List<CommandObserver> callbacks;

		private Object result;
		private CommandStatus status;

		public DefaultObservableCommand(Object command, Class<?> type, String id, boolean root) {
			this.command = command;
			this.type = type;
			this.root = root;
			this.id = id;
			this.status = CommandStatus.EXECUTE;
			this.callbacks = new LinkedList<CommandObserver>();
		}

		@Override
		public Message<Object> getTrigger() {
			return trigger;
		}

		@Override
		public Object getCommand() {
			return command;
		}

		@Override
		public String getId() {
			return id;
		}

		@Override
		public Class<?> getType() {
			return type;
		}

		@Override
		public Object getResult() {
			return result;
		}

		public void setResult(CommandResult result) {
			this.status =
					(result.complete() ? CommandStatus.COMPLETE : (result.getValue() == null ? CommandStatus.CANCEL : CommandStatus.EXCEPTION));
			this.result = result.getValue();
			for (CommandObserver callback : callbacks) {
				callback.handleCommand(this);
			}
			callbacks.clear();
		}

		@Override
		public CommandStatus getStatus() {
			return status;
		}

		@Override
		public boolean isRoot() {
			return root;
		}

		@Override
		public void observe(CommandObserver callback) {
			callbacks.add(callback);
		}

	}
}
