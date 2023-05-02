// from https://github.com/VirtusLab/scala-cli/blob/e4fb5711f8f9f31e5cacff3a636919347af49533/modules/build/src/main/java/scala/build/internal/JniGetWinDirs.java

package almond.internals;

import java.util.ArrayList;

import coursier.cache.shaded.dirs.GetWinDirs;
import coursier.jniutils.WindowsKnownFolders;

public class JniGetWinDirs implements GetWinDirs {
  @Override
  public String[] getWinDirs(String... guids) {
    ArrayList<String> list = new ArrayList<>();
    for (int i = 0; i < guids.length; i++) {
      list.add(WindowsKnownFolders.knownFolderPath("{" + guids[i] + "}"));
    }
    return list.toArray(new String[list.size()]);
  }
}
