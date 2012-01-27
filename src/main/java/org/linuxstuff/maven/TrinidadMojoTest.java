package org.linuxstuff.maven;

import java.io.File;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.Test;

public class TrinidadMojoTest {

	@Test
	public void runTest() throws MojoExecutionException {
		TrinidadMojo tm = new TrinidadMojo();
		tm.setResultRepositoryUri(new File(System.getProperty("java.io.tmpdir"), "fitnesse").getAbsolutePath());
		tm.setTestRepositoryUri("/home/goyqo/work/fitnesse");
		tm.setSingleSuite("FitNesse.SuiteAcceptanceTests.SuiteSlimTests");
		tm.execute();
	}
}
