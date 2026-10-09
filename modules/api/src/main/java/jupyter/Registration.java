// from https://github.com/jupyter/jvm-repr/blob/2e50ad4f70bde84018a1f1ee469517d2b63cd343/src/main/java/jupyter/Registration.java
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;

/**
 * Handles registration of {@link Displayer} instances.
 * <p>
 * Callers should use the singleton instance of this class, which is available
 * from {@link Displayers#registration()}.
 */
public class Registration {

  static final Registration INSTANCE = new Registration();

  private final Map<Class<?>, Displayer<?>> displayers = new HashMap<>();
  private Displayer<Object> defaultDisplayer = ToStringDisplayer.get();
  private String[] mimeTypes = null;

  private static Displayer<AsDisplayData> asDisplayDataDisplayer = new Displayer<AsDisplayData>() {
    public Map<String, String> display(AsDisplayData obj) {
      return obj.display();
    }
  };

  private void init() {
    add(AsDisplayData.class, asDisplayDataDisplayer);
  }

  public Registration() {
    init();
  }

  public Map<Class<?>, Displayer<?>> getAll() {
    return Collections.unmodifiableMap(displayers);
  }

  /**
   * Sets the MIME type hint for all registered {@link Displayer} instances.
   *
   * @param types supported MIME types
   */
  public void setMimeTypes(String... types) {
    this.mimeTypes = types;
    for (Displayer<?> displayer : displayers.values()) {
      displayer.setMimeTypes(types);
    }
    if (defaultDisplayer != null) {
      defaultDisplayer.setMimeTypes(types);
    }
  }

  /**
   * Sets the default {@link Displayer} instance. This is used to display any
   * {@link Object} with no more specific displayer.
   *
   * @param displayer a Displayer for any object.
   */
  public void setDefault(Displayer<Object> displayer) {
    this.defaultDisplayer = displayer;
  }

  /**
   * Registers a Displayer instance for a class.
   *
   * @param objClass the class of objects to display
   * @param displayer a Displayer instance
   */
  public <T> void add(Class<T> objClass, Displayer<? extends T> displayer) {
    if (mimeTypes != null) {
      displayer.setMimeTypes(mimeTypes);
    }
    displayers.put(objClass, displayer);
  }

  /**
   * Finds the most specific Displayer instance for a class.
   * <p>
   * A displayer is found by checking registrations for the given class, its interfaces, its
   * superclasses, and each superclass's interfaces using a breadth-first search. The search visits
   * a class, then its interfaces in left-to-right order, then its superclass, the superclass's
   * interfaces, and so on.
   * <p>
   * The first displayer that can handle the class will be returned.
   *
   * @param objClass the class of objects to display
   * @return a Displayer instance for this class or one of its superclasses.
   */
  @SuppressWarnings("unchecked")
  public <T> Displayer<? super T> find(Class<T> objClass) {
    Set<Class<?>> visited = new HashSet<>();
    visited.add(Object.class); // stop search with Object
    LinkedList<Class<? super T>> classes = new LinkedList<>();
    classes.addLast(objClass);

    while (!classes.isEmpty()) {
      Class<? super T> currentClass = classes.removeFirst();
      Displayer<?> displayer = displayers.get(currentClass);
      if (displayer != null) {
        return (Displayer<? super T>) displayer;
      }

      for (Class<?> iface : currentClass.getInterfaces()) {
        if (!visited.contains(iface)) {
          classes.add((Class<? super T>) iface);
        }
      }

      Class<? super T> superClass = currentClass.getSuperclass();
      // interface superclasses can be null
      if (superClass != null && !visited.contains(superClass)) {
        classes.add(superClass);
      }
    }

    return defaultDisplayer;
  }

  // Visible for testing
  void clear() {
    displayers.clear();
    defaultDisplayer = ToStringDisplayer.get();
    init();
  }
}
