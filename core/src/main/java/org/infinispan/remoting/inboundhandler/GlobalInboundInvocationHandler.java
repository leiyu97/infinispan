package org.infinispan.remoting.inboundhandler;

import static org.infinispan.factories.KnownComponentNames.REMOTE_COMMAND_EXECUTOR;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

import org.infinispan.IllegalLifecycleStateException;
import org.infinispan.commands.CommandsFactory;
import org.infinispan.commands.ReplicableCommand;
import org.infinispan.commands.remote.CacheRpcCommand;
import org.infinispan.commons.CacheException;
import org.infinispan.factories.ComponentRegistry;
import org.infinispan.factories.GlobalComponentRegistry;
import org.infinispan.factories.annotations.ComponentName;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.factories.scopes.Scope;
import org.infinispan.factories.scopes.Scopes;
import org.infinispan.remoting.responses.CacheNotFoundResponse;
import org.infinispan.remoting.responses.ExceptionResponse;
import org.infinispan.remoting.responses.Response;
import org.infinispan.remoting.responses.SuccessfulResponse;
import org.infinispan.remoting.transport.Address;
import org.infinispan.util.ByteString;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;
import org.infinispan.xsite.BackupReceiver;
import org.infinispan.xsite.BackupReceiverRepository;
import org.infinispan.xsite.XSiteReplicateCommand;

/**
 * {@link org.infinispan.remoting.inboundhandler.InboundInvocationHandler} implementation that handles all the {@link
 * org.infinispan.commands.ReplicableCommand}.
 * <p/>
 * This component handles the {@link org.infinispan.commands.ReplicableCommand} from local and remote site. The remote
 * site {@link org.infinispan.commands.ReplicableCommand} are sent to the {@link org.infinispan.xsite.BackupReceiver} to
 * be handled.
 * <p/>
 * Also, the non-{@link org.infinispan.commands.remote.CacheRpcCommand} are processed directly and the {@link
 * org.infinispan.commands.remote.CacheRpcCommand} are processed in the cache's {@link
 * org.infinispan.remoting.inboundhandler.PerCacheInboundInvocationHandler} implementation.
 *
 * @author Pedro Ruivo
 * @since 7.1
 */
@Scope(Scopes.GLOBAL)
public class GlobalInboundInvocationHandler implements InboundInvocationHandler {

   private static final Log log = LogFactory.getLog(GlobalInboundInvocationHandler.class);
   private static final boolean trace = log.isTraceEnabled();

   private ExecutorService remoteCommandsExecutor;
   private BackupReceiverRepository backupReceiverRepository;
   private GlobalComponentRegistry globalComponentRegistry;

   private static Response shuttingDownResponse() {
      return CacheNotFoundResponse.INSTANCE;
   }

   public static ExceptionResponse exceptionHandlingCommand(Throwable throwable) {
      return new ExceptionResponse(new CacheException("Problems invoking command.", throwable));
   }

   @Inject
   public void injectDependencies(@ComponentName(REMOTE_COMMAND_EXECUTOR) ExecutorService remoteCommandsExecutor,
                                  GlobalComponentRegistry globalComponentRegistry,
                                  BackupReceiverRepository backupReceiverRepository) {
      this.remoteCommandsExecutor = remoteCommandsExecutor;
      this.globalComponentRegistry = globalComponentRegistry;
      this.backupReceiverRepository = backupReceiverRepository;
   }

   @Override
   public void handleFromCluster(Address origin, ReplicableCommand command, Reply reply, DeliverOrder order) {
      try {
         if (command instanceof CacheRpcCommand) {
            handleCacheRpcCommand(origin, (CacheRpcCommand) command, reply, order);
         } else {
            if (trace) {
               log.tracef("Attempting to execute non-CacheRpcCommand: %s [sender=%s]", command, origin);
            }
            Runnable runnable = create(command, reply, order.preserveOrder());
            if (order.preserveOrder() || !command.canBlock()) {
               runnable.run();
            } else {
               remoteCommandsExecutor.execute(runnable);
            }
         }
      } catch (Throwable t) {
         log.exceptionHandlingCommand(command, t);
         reply.reply(exceptionHandlingCommand(t));
      }
   }

   @Override
   public void handleFromRemoteSite(String origin, XSiteReplicateCommand command, Reply reply, DeliverOrder order) {
      if (trace) {
         log.tracef("Handling command %s from remote site %s", command, origin);
      }

      BackupReceiver receiver = backupReceiverRepository.getBackupReceiver(origin, command.getCacheName().toString());
      Runnable runnable = create(command, receiver, reply);
      if (order.preserveOrder()) {
         runnable.run();
      } else {
         //the remote site commands may need to be forwarded to the appropriate owners
         remoteCommandsExecutor.execute(runnable);
      }
   }

   private void handleCacheRpcCommand(Address origin, CacheRpcCommand command, Reply reply, DeliverOrder mode) {
      command.setOrigin(origin);
      if (trace) {
         log.tracef("Attempting to execute CacheRpcCommand: %s [sender=%s]", command, origin);
      }
      ByteString cacheName = command.getCacheName();
      ComponentRegistry cr = globalComponentRegistry.getNamedComponentRegistry(cacheName);

      if (cr == null) {
         if (trace) {
            log.tracef("Silently ignoring that %s cache is not defined", cacheName);
         }
         reply.reply(CacheNotFoundResponse.INSTANCE);
         return;
      }
      initializeCacheRpcCommand(command, cr);
      PerCacheInboundInvocationHandler handler = cr.getPerCacheInboundInvocationHandler();
      handler.handle(command, reply, mode);
   }

   private void initializeCacheRpcCommand(CacheRpcCommand command, ComponentRegistry componentRegistry) {
      CommandsFactory commandsFactory = componentRegistry.getCommandsFactory();
      // initialize this command with components specific to the intended cache instance
      commandsFactory.initializeReplicableCommand(command, true);
   }

   private Runnable create(final XSiteReplicateCommand command, final BackupReceiver receiver, final Reply reply) {
      return new Runnable() {
         @Override
         public void run() {
            try {
               reply.reply(command.performInLocalSite(receiver));
            } catch (InterruptedException e) {
               log.shutdownHandlingCommand(command);
               reply.reply(shuttingDownResponse());
            } catch (Throwable throwable) {
               log.exceptionHandlingCommand(command, throwable);
               reply.reply(exceptionHandlingCommand(throwable));
            }
         }
      };
   }

   private Runnable create(final ReplicableCommand command, final Reply reply, boolean preserveOrder) {
      return new Runnable() {
         @Override
         public void run() {
            try {
               globalComponentRegistry.wireDependencies(command);

               CompletableFuture<Object> future = command.invokeAsync();
               if (preserveOrder) {
                  future.join();
               } else {
                  future.whenComplete((retVal, throwable) -> {
                     if (retVal != null && !(retVal instanceof Response)) {
                        retVal = SuccessfulResponse.create(retVal);
                     }
                     reply.reply(retVal);
                  });
               }
            } catch (Throwable throwable) {
               if (throwable.getCause() != null && throwable instanceof CompletionException) {
                  throwable = throwable.getCause();
               }
               if (throwable instanceof InterruptedException || throwable instanceof IllegalLifecycleStateException) {
                  log.shutdownHandlingCommand(command);
                  reply.reply(shuttingDownResponse());
               } else {
                  log.exceptionHandlingCommand(command, throwable);
                  reply.reply(exceptionHandlingCommand(throwable));
               }
            }
         }
      };
   }
}
