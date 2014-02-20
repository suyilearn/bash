package org.crashub.bash.spi;

import org.crashub.bash.ir.Node;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * A base context implementation that uses {@link java.io.InputStream} and {@link java.io.OutputStream} streams. Should
 * be used for implementing runtimes based on <code>java.io</code> package.
 *
 * @author Julien Viet
 */
public class BaseContext extends Context {

  /**
   * Provides command plugability.
   */
  public interface CommandResolver {

    /**
     * Resolve a command implementation.
     *
     * @param command the command name
     * @param parameters the command parameters
     * @param standardInput the standard input
     * @param standardOutput the standard output
     * @return a callable invoking the command or null if the command could not be resolved
     */
    Callable<?> resolveCommand(String command, List<String> parameters, InputStream standardInput, OutputStream standardOutput);

  }

  final CommandResolver commandResolver;
  final Map<String, Function> functions;
  protected final InputStream standardInput;
  protected final OutputStream standardOutput;
  protected final Scope bindingScope;

  public BaseContext(CommandResolver commandResolver, OutputStream standardOutput) {
    this(commandResolver, new ByteArrayInputStream(new byte[0]), standardOutput);
  }

  public BaseContext(CommandResolver commandResolver, InputStream standardInput, OutputStream standardOutput) {
    this(commandResolver, standardInput, standardOutput, new SimpleScope());
  }

  public BaseContext(CommandResolver commandResolver, InputStream standardInput, OutputStream standardOutput, Scope bindingScope) {
    this.commandResolver = commandResolver;
    this.standardInput = standardInput;
    this.standardOutput = standardOutput;
    this.bindingScope = bindingScope;
    this.functions = new HashMap<String, Function>();
  }

  @Override
  public void setFunction(String name, Function function) {
    if (function != null) {
      functions.put(name, function);
    } else {
      functions.remove(name);
    }
  }

  @Override
  public Function getFunction(String name) {
    return functions.get(name);
  }

  @Override
  public final Node createCommand(final String command, final List<String> parameters) {
    Function function = functions.get(command);
    if (function != null) {
      return function.bind(parameters);
    } else {
      return new Node() {
        @Override
        public Object eval(Scope bindings, Context context) {
          BaseContext nested = (BaseContext)context;
          Callable<?> impl = commandResolver.resolveCommand(
              command,
              parameters,
              nested.standardInput,
              nested.standardOutput);
          if (impl == null) {
            throw new UnsupportedOperationException("Command " + command + " not implemented");
          }
          try {
            return impl.call();
          }
          catch (Exception e) {
            // Should be done better later when we take care of exception handling
            throw new UndeclaredThrowableException(e);
          }
        }
      };
    }
  }

  @Override
  public final Object execute(Scope bindings, Node[] pipeline) {
    Object last = null;
    InputStream in = standardInput;
    for (int i = 0;i < pipeline.length;i++) {
      Node process = pipeline[i];
      if (i == pipeline.length - 1) {
        last = process.eval(bindings, new BaseContext(commandResolver, in, standardOutput, bindingScope));
        try {
          standardOutput.flush();
        }
        catch (IOException e) {
          throw new UndeclaredThrowableException(e, "Handle me gracefully");
        }
      } else {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        last = process.eval(bindings, new BaseContext(commandResolver, in, buffer, bindingScope));
        try {
          buffer.close();
        }
        catch (IOException e) {
          throw new UndeclaredThrowableException(e, "Handle me gracefully");
        }
        in = new ByteArrayInputStream(buffer.toByteArray());
      }
    }
    return last;
  }
}
