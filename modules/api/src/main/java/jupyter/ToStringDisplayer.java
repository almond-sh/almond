// from https://github.com/jupyter/jvm-repr/blob/2e50ad4f70bde84018a1f1ee469517d2b63cd343/src/main/java/jupyter/ToStringDisplayer.java
// (the jvm-repr repository is licensed under the terms in META-INF/LICENSE-jvm-repr, in our resources)

/*
 * Copyright 2017 Netflix
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jupyter;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

class ToStringDisplayer extends Displayer<Object> {

  private static final ToStringDisplayer INSTANCE = new ToStringDisplayer();

  public static Displayer<Object> get() {
    return INSTANCE;
  }

  @Override
  public Map<String, String> display(Object obj) {
    Map<String, String> result = new HashMap<>();

    if (obj.getClass().isArray()) {
      result.put("text/plain", displayArray(obj));
    } else {
      result.put("text/plain", obj.toString());
    }

    return result;
  }

  private String displayArray(Object obj) {
    Class<?> type = obj.getClass().getComponentType();
    if (type == Boolean.TYPE) {
      return Arrays.toString((boolean[]) obj);
    } else if (type == Byte.TYPE) {
      return Arrays.toString((byte[]) obj);
    } else if (type == Short.TYPE) {
      return Arrays.toString((short[]) obj);
    } else if (type == Integer.TYPE) {
      return Arrays.toString((int[]) obj);
    } else if (type == Long.TYPE) {
      return Arrays.toString((long[]) obj);
    } else if (type == Float.TYPE) {
      return Arrays.toString((float[]) obj);
    } else if (type == Double.TYPE) {
      return Arrays.toString((double[]) obj);
    } else if (type == Character.TYPE) {
      return Arrays.toString((char[]) obj);
    } else {
      Object[] arr = (Object[]) obj;
      StringBuilder sb = new StringBuilder();

      sb.append("[");
      if (arr.length > 0) {
        sb.append(displayElement(arr[0]));
        for (int i = 1; i < arr.length; i += 1) {
          String asText = displayElement(arr[i]);
          sb.append(", ").append(asText);
        }
      }
      sb.append("]");

      return sb.toString();
    }
  }

  private String displayElement(Object obj) {
    // Displayers.display doesn't accept nulls
    return obj != null ? Displayers.display(obj).get("text/plain") : "null";
  }
}
