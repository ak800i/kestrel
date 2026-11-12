package dev.kestrel.build.audit.fixtures;

import java.io.InputStream;
import java.net.URL;
import java.util.concurrent.Callable;

// javac turns this method reference into an invokedynamic instruction that points straight at URL.openStream.
public class JavaMethodReference {
    public Callable<InputStream> opener(URL url) {
        return url::openStream;
    }
}
