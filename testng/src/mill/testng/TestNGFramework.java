package mill.testng;



import sbt.testing.*;


public class TestNGFramework implements Framework {
    public String name(){ return "TestNG"; }

    public Fingerprint[] fingerprints() {
        return new Fingerprint[]{TestNGFingerprint.instance};
    }

    @Override
    public Runner runner(String[] args, String[] remoteArgs, ClassLoader classLoader) {
        return new TestNGRunner(args, remoteArgs, classLoader);
    }
}

class TestNGFingerprint implements AnnotatedFingerprint{
    final public static TestNGFingerprint instance = new TestNGFingerprint();
    public String annotationName(){return "org.testng.annotations.Test";}
    public boolean isModule(){return false;}
}
