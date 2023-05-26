// https://github.com/VirtusLab/scala-cli/blob/230575fbadbdba51af20f6852151dba567f416b4/modules/build/src/main/java/scala/build/internal/Chdir.java

package scala.build.internal;

import coursier.jvm.ErrnoException;

public final class Chdir {

  public static boolean available() {
    return false;
  }

  public static void chdir(String path) throws ErrnoException {
    // Not supported on the JVM, returning immediately
  }

}
