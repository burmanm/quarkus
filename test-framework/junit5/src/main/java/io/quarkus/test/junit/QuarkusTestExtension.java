package io.quarkus.test.junit;

import static io.quarkus.test.common.PathTestHelper.getAppClassLocation;
import static io.quarkus.test.common.PathTestHelper.getTestClassesLocation;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.junit.jupiter.api.extension.TestInstantiationException;
import org.opentest4j.TestAbortedException;

import io.quarkus.bootstrap.app.AdditionalDependency;
import io.quarkus.bootstrap.app.AugmentAction;
import io.quarkus.bootstrap.app.CuratedApplication;
import io.quarkus.bootstrap.app.QuarkusBootstrap;
import io.quarkus.bootstrap.app.RunningQuarkusApplication;
import io.quarkus.bootstrap.app.StartupAction;
import io.quarkus.builder.BuildChainBuilder;
import io.quarkus.builder.BuildContext;
import io.quarkus.builder.BuildStep;
import io.quarkus.deployment.builditem.TestAnnotationBuildItem;
import io.quarkus.deployment.builditem.TestClassPredicateBuildItem;
import io.quarkus.runtime.Timing;
import io.quarkus.test.common.PathTestHelper;
import io.quarkus.test.common.PropertyTestUtil;
import io.quarkus.test.common.RestAssuredURLManager;
import io.quarkus.test.common.TestResourceManager;
import io.quarkus.test.common.TestScopeManager;
import io.quarkus.test.common.http.TestHTTPResourceManager;

//todo: share common core with QuarkusUnitTest
public class QuarkusTestExtension
        implements BeforeEachCallback, AfterEachCallback, BeforeAllCallback, InvocationInterceptor, AfterAllCallback,
        ParameterResolver {

    protected static final String TEST_LOCATION = "test-location";
    private static boolean failedBoot;

    private static Class<?> actualTestClass;
    private static Object actualTestInstance;
    private static ClassLoader originalCl;
    private static RunningQuarkusApplication runningQuarkusApplication;
    private static Path testClassLocation;
    private static Throwable firstException; //if this is set then it will be thrown from the very first test that is run, the rest are aborted

    private ExtensionState doJavaStart(ExtensionContext context) throws Throwable {
        Closeable testResourceManager = null;
        try {
            final LinkedBlockingDeque<Runnable> shutdownTasks = new LinkedBlockingDeque<>();

            Path appClassLocation = getAppClassLocation(context.getRequiredTestClass());

            final QuarkusBootstrap.Builder runnerBuilder = QuarkusBootstrap.builder(appClassLocation)
                    .setIsolateDeployment(true)
                    .setMode(QuarkusBootstrap.Mode.TEST);

            originalCl = Thread.currentThread().getContextClassLoader();
            testClassLocation = getTestClassesLocation(context.getRequiredTestClass());

            if (!appClassLocation.equals(testClassLocation)) {
                runnerBuilder.addAdditionalApplicationArchive(new AdditionalDependency(testClassLocation, false, true, true));
            }
            CuratedApplication curatedApplication = runnerBuilder
                    .setTest(true)
                    .setProjectRoot(new File("").toPath())
                    .build()
                    .bootstrap();

            Timing.staticInitStarted(curatedApplication.getBaseRuntimeClassLoader());
            AugmentAction augmentAction = curatedApplication.createAugmentor(TestBuildChainFunction.class.getName(),
                    Collections.singletonMap(TEST_LOCATION, testClassLocation));
            StartupAction startupAction = augmentAction.createInitialRuntimeApplication();
            Thread.currentThread().setContextClassLoader(startupAction.getClassLoader());

            //must be done after the TCCL has been set
            testResourceManager = (Closeable) startupAction.getClassLoader().loadClass(TestResourceManager.class.getName())
                    .getConstructor(Class.class)
                    .newInstance(context.getRequiredTestClass());
            testResourceManager.getClass().getMethod("start").invoke(testResourceManager);

            runningQuarkusApplication = startupAction.run();

            ConfigProviderResolver.setInstance(new RunningAppConfigResolver(runningQuarkusApplication));

            System.setProperty("test.url", TestHTTPResourceManager.getUri(runningQuarkusApplication));

            Closeable tm = testResourceManager;
            Closeable shutdownTask = new Closeable() {
                @Override
                public void close() throws IOException {
                    try {
                        runningQuarkusApplication.close();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        try {
                            while (!shutdownTasks.isEmpty()) {
                                shutdownTasks.pop().run();
                            }
                        } finally {
                            tm.close();
                        }
                    }
                }
            };
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        shutdownTask.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        curatedApplication.close();
                    }
                }
            }, "Quarkus Test Cleanup Shutdown task"));
            return new ExtensionState(testResourceManager, shutdownTask);
        } catch (Throwable e) {

            try {
                if (testResourceManager != null) {
                    testResourceManager.close();
                }
            } catch (Exception ex) {
                e.addSuppressed(ex);
            }
            throw e;
        }
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        if (isNativeTest(context)) {
            return;
        }
        if (!failedBoot) {
            boolean nativeImageTest = isNativeTest(context);
            runningQuarkusApplication.getClassLoader().loadClass(RestAssuredURLManager.class.getName())
                    .getDeclaredMethod("clearURL").invoke(null);
            runningQuarkusApplication.getClassLoader().loadClass(TestScopeManager.class.getName())
                    .getDeclaredMethod("tearDown", boolean.class).invoke(null, nativeImageTest);
        }
    }

    private boolean isNativeTest(ExtensionContext context) {
        return context.getRequiredTestClass().isAnnotationPresent(NativeImageTest.class);
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        if (isNativeTest(context)) {
            return;
        }
        if (!failedBoot) {
            boolean nativeImageTest = isNativeTest(context);
            if (runningQuarkusApplication != null) {
                runningQuarkusApplication.getClassLoader().loadClass(RestAssuredURLManager.class.getName())
                        .getDeclaredMethod("setURL", boolean.class).invoke(null, false);
                runningQuarkusApplication.getClassLoader().loadClass(TestScopeManager.class.getName())
                        .getDeclaredMethod("setup", boolean.class).invoke(null, nativeImageTest);
            }
        } else {
            if (firstException != null) {
                Throwable throwable = firstException;
                firstException = null;
                throw new RuntimeException(throwable);
            } else {
                throw new TestAbortedException("Boot failed");
            }
        }
    }

    private ExtensionState ensureStarted(ExtensionContext extensionContext) {
        ExtensionContext root = extensionContext.getRoot();
        ExtensionContext.Store store = root.getStore(ExtensionContext.Namespace.GLOBAL);
        ExtensionState state = store.get(ExtensionState.class.getName(), ExtensionState.class);
        if (state == null && !failedBoot) {
            PropertyTestUtil.setLogFileProperty();
            try {
                state = doJavaStart(extensionContext);
                store.put(ExtensionState.class.getName(), state);

            } catch (Throwable e) {
                failedBoot = true;
                firstException = e;
            }
        }
        return state;
    }

    private static ClassLoader setCCL(ClassLoader cl) {
        final Thread thread = Thread.currentThread();
        final ClassLoader original = thread.getContextClassLoader();
        thread.setContextClassLoader(cl);
        return original;
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        if (isNativeTest(context)) {
            return;
        }
        ensureStarted(context);
        if (runningQuarkusApplication != null) {
            setCCL(runningQuarkusApplication.getClassLoader());
        }
    }

    @Override
    public void interceptBeforeAllMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext,
            ExtensionContext extensionContext) throws Throwable {
        if (isNativeTest(extensionContext)) {
            invocation.proceed();
            return;
        }
        ensureStarted(extensionContext);
        runExtensionMethod(invocationContext, extensionContext);
        invocation.skip();
    }

    @Override
    public <T> T interceptTestClassConstructor(Invocation<T> invocation,
            ReflectiveInvocationContext<Constructor<T>> invocationContext, ExtensionContext extensionContext) throws Throwable {
        if (isNativeTest(extensionContext)) {
            return invocation.proceed();
        }
        T result;
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        Class<?> requiredTestClass = extensionContext.getRequiredTestClass();
        try {
            Thread.currentThread().setContextClassLoader(requiredTestClass.getClassLoader());
            result = invocation.proceed();
        } catch (NullPointerException e) {
            throw new RuntimeException(
                    "When using constructor injection in a test, the only legal operation is to assign the constructor values to fields. Offending class is "
                            + requiredTestClass,
                    e);
        } finally {
            Thread.currentThread().setContextClassLoader(old);
        }
        ExtensionState state = ensureStarted(extensionContext);
        if (failedBoot) {
            return result;
        }

        // We do this here as well, because when @TestInstance(Lifecycle.PER_CLASS) is used on a class,
        // interceptTestClassConstructor is called before beforeAll, meaning that the TCCL will not be set correctly
        // (for any test other than the first) unless this is done
        if (runningQuarkusApplication != null) {
            setCCL(runningQuarkusApplication.getClassLoader());
        }

        initTestState(extensionContext, state);
        return result;
    }

    private void initTestState(ExtensionContext extensionContext, ExtensionState state) {
        try {
            actualTestClass = Class.forName(extensionContext.getRequiredTestClass().getName(), true,
                    Thread.currentThread().getContextClassLoader());

            actualTestInstance = runningQuarkusApplication.instance(actualTestClass);

            Class<?> resM = Thread.currentThread().getContextClassLoader().loadClass(TestHTTPResourceManager.class.getName());
            resM.getDeclaredMethod("inject", Object.class).invoke(null, actualTestInstance);
            state.testResourceManager.getClass().getMethod("inject", Object.class).invoke(state.testResourceManager,
                    actualTestInstance);
        } catch (Exception e) {
            throw new TestInstantiationException("Failed to create test instance", e);
        }
    }

    @Override
    public void interceptBeforeEachMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext,
            ExtensionContext extensionContext) throws Throwable {
        if (isNativeTest(extensionContext)) {
            invocation.proceed();
            return;
        }
        runExtensionMethod(invocationContext, extensionContext);
        invocation.skip();
    }

    @Override
    public void interceptTestMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext,
            ExtensionContext extensionContext) throws Throwable {
        if (isNativeTest(extensionContext)) {
            invocation.proceed();
            return;
        }
        runExtensionMethod(invocationContext, extensionContext);
        invocation.skip();
    }

    @Override
    public void interceptTestTemplateMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext,
            ExtensionContext extensionContext) throws Throwable {
        if (isNativeTest(extensionContext)) {
            invocation.proceed();
            return;
        }
        runExtensionMethod(invocationContext, extensionContext);
        invocation.skip();
    }

    @Override
    public <T> T interceptTestFactoryMethod(Invocation<T> invocation,
            ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        if (isNativeTest(extensionContext)) {
            return invocation.proceed();
        }
        T result = (T) runExtensionMethod(invocationContext, extensionContext);
        invocation.skip();
        return result;
    }

    @Override
    public void interceptAfterEachMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext,
            ExtensionContext extensionContext) throws Throwable {
        if (isNativeTest(extensionContext)) {
            invocation.proceed();
            return;
        }
        runExtensionMethod(invocationContext, extensionContext);
        invocation.skip();
    }

    @Override
    public void interceptAfterAllMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext,
            ExtensionContext extensionContext) throws Throwable {
        if (isNativeTest(extensionContext)) {
            invocation.proceed();
            return;
        }
        runExtensionMethod(invocationContext, extensionContext);
        invocation.skip();
    }

    private Object runExtensionMethod(ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext)
            throws Throwable {
        Method newMethod = null;

        try {
            Class<?> c = Class.forName(extensionContext.getRequiredTestClass().getName(), true,
                    Thread.currentThread().getContextClassLoader());
            ;
            while (c != Object.class) {
                if (c.getName().equals(invocationContext.getExecutable().getDeclaringClass().getName())) {
                    try {
                        Class<?>[] originalParameterTypes = invocationContext.getExecutable().getParameterTypes();
                        List<Class<?>> parameterTypesFromTccl = new ArrayList<>(originalParameterTypes.length);
                        for (Class<?> type : originalParameterTypes) {
                            if (type.isPrimitive()) {
                                parameterTypesFromTccl.add(type);
                            } else {
                                parameterTypesFromTccl
                                        .add(Class.forName(type.getName(), true,
                                                Thread.currentThread().getContextClassLoader()));
                            }
                        }
                        newMethod = c.getDeclaredMethod(invocationContext.getExecutable().getName(),
                                parameterTypesFromTccl.toArray(new Class[0]));
                        break;
                    } catch (NoSuchMethodException e) {
                        //ignore
                    }
                }
                c = c.getSuperclass();
            }
            if (newMethod == null) {
                throw new RuntimeException("Could not find method " + invocationContext.getExecutable() + " on test class");
            }
            newMethod.setAccessible(true);

            // the arguments were not loaded from TCCL so we need to try and "convert" if possible
            // most of the time this won't be possible or necessary, but for the widely used enum case we need to do it
            // this is a total hack, but...
            List<Object> originalArguments = invocationContext.getArguments();
            List<Object> argumentsFromTccl = new ArrayList<>();
            for (Object arg : originalArguments) {
                if (arg != null && arg.getClass().isEnum()) {
                    argumentsFromTccl.add(Enum.valueOf((Class<Enum>) Class.forName(arg.getClass().getName(), false,
                            Thread.currentThread().getContextClassLoader()), arg.toString()));
                } else {
                    // we can't do anything but hope for the best...
                    argumentsFromTccl.add(arg);
                }
            }

            return newMethod.invoke(actualTestInstance, argumentsFromTccl.toArray(new Object[0]));
        } catch (InvocationTargetException e) {
            throw e.getCause();
        } catch (IllegalAccessException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        if (originalCl != null) {
            setCCL(originalCl);
        }
    }

    /**
     * Return true if we need a parameter for constructor injection
     */
    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        return parameterContext.getDeclaringExecutable() instanceof Constructor;
    }

    /**
     * We don't actually have to resolve the parameter (thus the default values in the implementation)
     * since the class instance that is passed to JUnit isn't really used.
     * The actual test instance that is used is the one that is pulled from Arc, which of course will already have its
     * constructor parameters properly resolved
     */
    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        String className = parameterContext.getParameter().getType().getName();
        switch (className) {
            case "boolean":
                return false;
            case "byte":
            case "short":
            case "int":
                return 0;
            case "long":
                return 0L;
            case "float":
                return 0.0f;
            case "double":
                return 0.0d;
            case "char":
                return '\u0000';
            default:
                return null;
        }
    }

    class ExtensionState implements ExtensionContext.Store.CloseableResource {

        private final Closeable testResourceManager;
        private final Closeable resource;

        ExtensionState(Closeable testResourceManager, Closeable resource) {
            this.testResourceManager = testResourceManager;
            this.resource = resource;
        }

        @Override
        public void close() throws Throwable {
            try {
                resource.close();
            } finally {
                if (QuarkusTestExtension.this.originalCl != null) {
                    setCCL(QuarkusTestExtension.this.originalCl);
                }
                testResourceManager.close();
            }
        }
    }

    public static class TestBuildChainFunction implements Function<Map<String, Object>, List<Consumer<BuildChainBuilder>>> {

        @Override
        public List<Consumer<BuildChainBuilder>> apply(Map<String, Object> stringObjectMap) {
            Path testLocation = (Path) stringObjectMap.get(TEST_LOCATION);
            return Collections.singletonList(new Consumer<BuildChainBuilder>() {
                @Override
                public void accept(BuildChainBuilder buildChainBuilder) {
                    buildChainBuilder.addBuildStep(new BuildStep() {
                        @Override
                        public void execute(BuildContext context) {
                            context.produce(new TestClassPredicateBuildItem(new Predicate<String>() {
                                @Override
                                public boolean test(String className) {
                                    return PathTestHelper.isTestClass(className,
                                            Thread.currentThread().getContextClassLoader(), testLocation);
                                }
                            }));
                        }
                    }).produces(TestClassPredicateBuildItem.class)
                            .build();

                    buildChainBuilder.addBuildStep(new BuildStep() {
                        @Override
                        public void execute(BuildContext context) {
                            context.produce(new TestAnnotationBuildItem(QuarkusTest.class.getName()));
                        }
                    }).produces(TestAnnotationBuildItem.class)
                            .build();
                }
            });
        }
    }
}
