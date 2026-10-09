// from https://github.com/jupyter/jvm-repr/blob/2e50ad4f70bde84018a1f1ee469517d2b63cd343/src/main/java/jupyter/Displayer.java
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

import java.util.Map;

/**
 * Converts objects to representations for display, by MIME type.
 *
 * @param <T> the class or interface of objects the instance can convert.
 */
public abstract class Displayer<T> {
  /**
   * Called to display an object.
   * <p>
   * This method should return a map of MIME type strings to representations of
   * the object in that MIME type.
   * <p>
   * This method can be called to display objects of type T or subtypes of T.
   * <p>
   * To avoid extra conversion, kernels or front-ends can call
   * {@link #setMimeTypes(String...)} to pass supported MIME types.
   * Implementations may ignore these MIME type hints.
   *
   * @param obj an object instance to display
   * @return a Map of representations of this object by MIME type
   */
  public abstract Map<String, String> display(T obj);

  /**
   * Called to pass the MIME types supported by the kernel or front-end.
   * <p>
   * Implementations may ignore these MIME type hints.
   *
   * @param types MIME types that are supported by the kernel
   */
  public void setMimeTypes(String... types) {
  }
}
