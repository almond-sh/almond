// from https://github.com/VirtusLab/scala-cli/blob/230575fbadbdba51af20f6852151dba567f416b4/modules/build/src/main/java/scala/build/internal/GraalvmUnistdExtras.java

package scala.build.internal;

import com.oracle.svm.core.posix.headers.PosixDirectives;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CCharPointerPointer;

@CContext(PosixDirectives.class)
@Platforms({Platform.LINUX.class, Platform.DARWIN.class})
public class GraalvmUnistdExtras {

    @CFunction
    public static native int chdir(CCharPointer path);

}
