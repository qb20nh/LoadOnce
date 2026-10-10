package com.qb20nh.loadonce.launcher;

/** Dependency-free executable contracts, including real child-process decisions. */
public final class LauncherContractSuite {
    private LauncherContractSuite() {}

    public static void main(String[] args) throws Exception {
        LauncherFilesTest.main(new String[0]);
        AotLauncherTest.main(new String[0]);
        CracFixtureTest.main(new String[0]);
        System.out.println("Java launcher contract suite passed");
    }
}
