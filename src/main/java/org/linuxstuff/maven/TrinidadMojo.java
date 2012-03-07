package org.linuxstuff.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import fitnesse.junit.*;
import fitnesse.responders.run.ResultsListener;

/**
 * Goal which runs a trinidad test execution.
 * 
 * @goal run-tests
 * @phase integration-test
 * @requiresDependencyResolution test
 */
public class TrinidadMojo extends AbstractMojo {
	/**
	 * this is an embedded resource parameter, it is set automatically using maven - ignore it
	 * 
	 * @parameter expression="${project}"
	 */
	private org.apache.maven.project.MavenProject mavenProject;

	/**
	 * The URI for the result repository (output directory if using a file result repository).
	 * 
	 * @parameter expression="${trinidad.output}" default-value="${project.build.directory}/trinidad"
	 * @required
	 */
	private String resultRepositoryUri;

	/**
	 * The URI for the test repository. The format depends on the test repository type. For fitnesse test repositories, this is the path to the main
	 * fitnesse
	 * 
	 * @parameter expression="${trinidad.test.location}"
	 * @required
	 */
	private String testRepositoryUri;

	/**
	 * Set this to true for Maven to break the build if any of the tests have failed. Leave it set to false to just print out a warning
	 * 
	 * @parameter default-value="false"
	 */
	private boolean breakBuildOnFailure;

	/**
	 * Set this to true for Maven to stop running tests after the first failed test/suite. Leave it set to false to run all tests regardless of
	 * failures.
	 * 
	 * @parameter default-value="false"
	 */
	private boolean stopAfterFirstFailure;

	/**
	 * A set of suite names to execute.
	 * 
	 * @parameter
	 */
	private String[] suites = new String[0];

	/**
	 * A set of test names to execute
	 * 
	 * @parameter
	 */
	private String[] tests = new String[0];

	/**
	 * a single test to execute
	 * 
	 * @parameter alias="test" expression="${trinidad.run.test}"
	 */
	private String singleTest = null;

	/**
	 * a single test to execute
	 * 
	 * @parameter alias="suite" expression="${trinidad.run.suite}"
	 */
	private String singleSuite = null;

	/**
	 * Whether or not to skip test run. This aligns with how surefire works.
	 * 
	 * @parameter default-value="false" expression="${maven.test.skip}"
	 */
	private boolean skipTest;

	/**
	 * Optional listener class name for extending test instrumentation and reporting. The class should implement FitNesse ResultListener interface
	 * 
	 * @parameter default-value="" expression="${trinidad.listener}"
	 */
	private String listenerClass;
	
	/**
	 * @parameter default-value="" expression="${trinidad.suite.filter}"
	 * @see #getSuiteFilter()
	 */
	private String suiteFilter;
	
	/**
	 * @parameter default-value="" expression="${trinidad.exclude.filter}"
	 * @see #getExcludeFilter()
	 */
	private String excludeFilter;
	
	/**
	 * Constructor.
	 */
	public TrinidadMojo() {
		suiteFilter = null;
		excludeFilter = null;
	}
	
	void setTestRepositoryUri(String testRepositoryUri) {
		this.testRepositoryUri = testRepositoryUri;
	}

	void setResultRepositoryUri(String resultRepositoryUri) {
		this.resultRepositoryUri = resultRepositoryUri;
	}

	void setSingleSuite(String singleSuite) {
		this.singleSuite = singleSuite;
	}

	void setSingleTest(String singleTest) {
		this.singleTest = singleTest;
	}
	
	/**
	 * @return A list of suite names separated by commas. Tests with the same suite names will be run (unless excluded).
	 * Special case: An empty String will match all tests.
	 */
	private String getSuiteFilter() {
		if (suiteFilter == null || suiteFilter.trim().isEmpty()) {
			return null;
		} else {
			return suiteFilter;
		}
	}

	/**
	 * @return A list of suite names separated by commas. Tests with the same suite names will not be run.
	 * Special case: An empty String will match no tests.
	 */
	private String getExcludeFilter() {
		if (excludeFilter == null || excludeFilter.trim().isEmpty()) {
			return null;
		} else {
			return excludeFilter;
		}
	}

	public void execute() throws MojoExecutionException {
		if (this.skipTest) {
			getLog().info("Skipping tests.");
			return;
		}

		processDefaults();
		createOutputDirectory();
		final ClassLoader cl = initProjectTestClassLoader();
		Thread runnerThread = new Thread(new Runnable() {
			public void run() {
				try {
					Object testRunnerInstance = loadTestRunner(cl);
					runIndividualTests(testRunnerInstance);
					runSuites(testRunnerInstance);
				} catch (Exception e) {
					throw new Error(e);
				}
			}
		});
		runnerThread.setContextClassLoader(cl);
		runnerThread.start();
		try {
			runnerThread.join();
		} catch (InterruptedException e) {
			getLog().error(e);
			throw new MojoExecutionException("exception in the mojo runner thread", e);
		}
		if (breakBuildOnFailure && totalWrongOrException > 0)
			throw new MojoExecutionException("Acceptance test run failed. Total " + totalWrongOrException
					+ " failing tests or exceptions. See log for more information.");
	}

	private void createOutputDirectory() {
		File output = new File(this.resultRepositoryUri);
		if (!output.exists())
			output.mkdirs();
	}

	private void runSuites(Object testRunnerInstance)
		throws NoSuchMethodException, IllegalAccessException, InvocationTargetException, NoSuchFieldException, MojoExecutionException {
		Method runSuite = testRunnerInstance.getClass().getMethod("run", String.class, String.class, String.class, String.class, int.class);
		for (String suite : suites) {
			if (stopAfterFirstFailure && totalWrongOrException > 0)
				break;
			Object counts;
			getLog().info("running suite=" + suite + ", suiteFilter=" + getSuiteFilter() + ", excludeFilter=" + getExcludeFilter());
			counts = runSuite.invoke(testRunnerInstance, suite, TestHelper.PAGE_TYPE_SUITE, getSuiteFilter(), getExcludeFilter(), 0);
			int wrongOrException = getWrongPlusExceptions(counts);
			totalWrongOrException += wrongOrException;
		}
	}

	private int totalWrongOrException = 0;

	private void runIndividualTests(Object testRunnerInstance) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException,
			NoSuchFieldException, MojoExecutionException {
		Method runTest = testRunnerInstance.getClass().getMethod("runTest", String.class);
		for (String test : tests) {
			if (stopAfterFirstFailure && totalWrongOrException > 0)
				break;
			getLog().info("running test=" + test);
			Object counts = runTest.invoke(testRunnerInstance, test);
			int wrongOrException = getWrongPlusExceptions(counts);
			totalWrongOrException += wrongOrException;
		}
	}

	private Object loadTestRunner(ClassLoader cl) throws ClassNotFoundException, InstantiationException, IllegalAccessException,
			NoSuchMethodException, InvocationTargetException {
		Object testRunnerInstance;
		Class<?> c = cl.loadClass(TestHelper.class.getName());
		if (listenerClass != null && !("".equals(listenerClass))) {
			Object listener = cl.loadClass(listenerClass).newInstance();
			getLog().debug("Loaded listener:" + listenerClass);
			testRunnerInstance = c.getConstructor(String.class, String.class, cl.loadClass(ResultsListener.class.getName())).newInstance(
					this.testRepositoryUri, this.resultRepositoryUri, listener);
		} else {
			testRunnerInstance = c.getConstructor(String.class, String.class).newInstance(this.testRepositoryUri, this.resultRepositoryUri);
		}
		getLog().debug("loaded test runner:" + testRunnerInstance);
		return testRunnerInstance;
	}

	private int getWrongPlusExceptions(Object counts) throws IllegalAccessException, NoSuchFieldException {
		int wrong = counts.getClass().getField("wrong").getInt(counts);
		int exceptions = counts.getClass().getField("exceptions").getInt(counts);
		int wrongOrException = wrong + exceptions;
		return wrongOrException;
	}

	private void processDefaults() {
		if (singleTest != null)
			tests = new String[] { singleTest };
		if (singleSuite != null)
			suites = new String[] { singleSuite };
	}

	private ClassLoader initProjectTestClassLoader() throws MojoExecutionException {
		if (mavenProject == null)
			return getClass().getClassLoader();
		try {

			List<String> classpath = mavenProject.getTestClasspathElements();
			getLog().debug("class path=" + classpath);
			URL[] urlArray = new URL[classpath.size()];
			for (int i = 0; i < classpath.size(); i++) {
				urlArray[i] = new File(classpath.get(i)).toURI().toURL();
			}
			return new URLClassLoader(urlArray);
		} catch (Exception e) {
			throw new MojoExecutionException("class loader initialisation failed", e);
		}
	}

}
