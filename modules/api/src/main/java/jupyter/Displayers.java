// from https://github.com/jupyter/jvm-repr/blob/2e50ad4f70bde84018a1f1ee469517d2b63cd343/src/main/java/jupyter/Displayers.java
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

import java.util.Collections;
import java.util.Map;

/**
 * Convenience methods using common JVM registration.
 */
public abstract class Displayers {
  /**
   * Returns the common {@link Registration} in this JVM.
   */
  public static Registration registration() {
    return Registration.INSTANCE;
  }

  /**
   * Registers a Displayer instance for a class or interface in this JVM.
   *
   * @param objClass the class of objects to display
   * @param displayer a Displayer instance
   */
  public static <T> void register(Class<T> objClass, Displayer<? extends T> displayer) {
    registration().add(objClass, displayer);
  }

  /**
   * Sets the MIME type hint for all registered {@link Displayer} instances in
   * this JVM.
   *
   * @param types supported MIME types
   */
  public static void setMimeTypes(String... types) {
    registration().setMimeTypes(types);
  }

  /**
   * Converts an object to one or more displayable representations by MIME type.
   *
   * @param obj an Object to display
   * @return a Map of representations of the object, by MIME type.
   */
  @SuppressWarnings("unchecked")
  public static <T> Map<String, String> display(T obj) {
    Displayer<? super T> displayer = registration().find((Class<T>) obj.getClass());
    if (displayer != null) {
      return displayer.display(obj);
    } else {
      return Collections.emptyMap();
    }
  }
}
