/**
 * Copyright (c) 2011 KUBO Atsuhiro <kubo@iteman.jp>,
 * All rights reserved.
 *
 * This file is part of MakeGood.
 *
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License v1.0 which accompanies this
 * distribution, and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package com.piece_framework.makegood.launch;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.ParserConfigurationException;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IProcess;
import org.xml.sax.SAXException;

import com.piece_framework.makegood.core.result.TestCaseResult;
import com.piece_framework.makegood.core.result.TestSuiteResult;
import com.piece_framework.makegood.core.run.Failures;
import com.piece_framework.makegood.core.run.JUnitXMLReader;
import com.piece_framework.makegood.core.run.JUnitXMLReaderListener;
import com.piece_framework.makegood.core.run.Progress;

/**
 * @since 1.2.0
 */
public class TestLifecycle {
    private Progress progress = new Progress();
    private boolean hasErrors = false;
    private Failures failures = new Failures();
    private ILaunch launch;
    private JUnitXMLReader junitXMLReader;
    private Thread parserThread;
    private TestingTargets testingTargets = new TestingTargets();
    private List<String> processedFiles = new ArrayList<String>();
    private static TestLifecycle currentTestLifecycle;

    private TestLifecycle() {
        super();
    }

    public void start(ILaunch launch, JUnitXMLReaderListener junitXMLReaderListener) throws CoreException {
        this.launch = launch;

        junitXMLReader = new JUnitXMLReader(new File(MakeGoodLaunchConfigurationDelegate.getJUnitXMLFile(launch)));
        junitXMLReader.addListener(junitXMLReaderListener);

        parserThread = new Thread() {
            @Override
            public void run() {
                try {
                    junitXMLReader.read();
                } catch (ParserConfigurationException e) {
                    Activator.getDefault().getLog().log(new Status(IStatus.WARNING, Activator.PLUGIN_ID, e.getMessage(), e));
                } catch (SAXException e) {
                    hasErrors = true;
                } catch (IOException e) {
                    Activator.getDefault().getLog().log(new Status(IStatus.WARNING, Activator.PLUGIN_ID, e.getMessage(), e));
                }
            }
        };

        progress.start();
        parserThread.start();
    }

    public void end() {
        for (IProcess process: launch.getProcesses()) {
            int exitValue = 0;
            try {
                if (!process.isTerminated()) continue;
                exitValue = process.getExitValue();
            } catch (DebugException e) {
                Activator.getDefault().getLog().log(new Status(Status.WARNING, Activator.PLUGIN_ID, e.getMessage(), e));
            }

            if (exitValue != 0) {
                hasErrors = true;
            }

            break;
        }

        junitXMLReader.stop();

        // TODO Since PDT 2.1 always returns 0 from IProcess.getExitValue(), We decided to use SAXException to check whether or not a PHP process exited with a fatal error.
        try {
            parserThread.join();
        } catch (InterruptedException e) {
            Activator.getDefault().getLog().log(new Status(Status.WARNING, Activator.PLUGIN_ID, e.getMessage(), e));
        }

        progress.end();
    }

    public Progress getProgress() {
        return progress;
    }

    public boolean hasErrors() {
        return hasErrors;
    }

    public TestSuiteResult getResult() {
        if (junitXMLReader == null) return null;
        return junitXMLReader.getResult();
    }

    public Failures getFailures() {
        return failures;
    }

    public void endTest() {
        progress.markAsCompleted();
    }

    public void startFailure(TestCaseResult failure) {
        failures.markCurrentResultAsFailure();
    }

    public void endTestCase(TestCaseResult testCase) {
        progress.endTestCase();
        testCase.setTime(progress.getProcessTimeForTestCase());
        if (isFileFirstAccessed(testCase)) {
            markFileAsAccessed(testCase);
        }
    }

    public boolean hasFailures() {
        return progress.hasFailures();
    }

    public boolean isProgressInitialized() {
        return progress.isInitialized();
    }

    public void startTestCase(TestCaseResult testCase) {
        failures.addResult(testCase);
        progress.startTestCase();
    }

    public void initializeProgress(TestSuiteResult testSuite) {
        progress.initialize(testSuite);
    }

    public void startTestSuite(TestSuiteResult testSuite) {
        failures.addResult(testSuite);
    }

    public boolean validateLaunchIdentity(MakeGoodLaunch launch) {
        return this.launch.equals(launch);
    }

    public static void create() {
        currentTestLifecycle = new TestLifecycle();
    }

    public static void destroy() {
        currentTestLifecycle = null;
    }

    public static boolean isRunning() {
        return currentTestLifecycle != null;
    }

    public static TestLifecycle getInstance() {
        return currentTestLifecycle;
    }

    /**
     * @since 1.3.0
     */
    public TestingTargets getTestingTargets() {
        return testingTargets;
    }

    public boolean isFileFirstAccessed(TestCaseResult testCase) {
        String file = testCase.getFile();
        if (file == null) return false;
        return !processedFiles.contains(file);
    }

    private void markFileAsAccessed(TestCaseResult testCase) {
        processedFiles.add(testCase.getFile());
    }
}
