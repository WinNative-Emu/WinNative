package com.winlator.cmod.runtime.input.controls;

import android.content.Context;
import androidx.annotation.NonNull;
import com.winlator.cmod.runtime.input.ui.InputControlsView;
import com.winlator.cmod.shared.io.FileUtils;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class ControlsProfile implements Comparable<ControlsProfile> {
  public final int id;
  private String name;
  private float cursorSpeed = 1.0f;
  private volatile List<ControlElement> elements = new ArrayList<>();
  private final ArrayList<ExternalController> controllers = new ArrayList<>();
  private boolean elementsLoaded = false;
  private boolean controllersLoaded = false;
  private boolean virtualGamepad = false;
  private final Context context;
  private GamepadState gamepadState;

  public ControlsProfile(Context context, int id) {
    this.context = context;
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public float getCursorSpeed() {
    return cursorSpeed;
  }

  public void setCursorSpeed(float cursorSpeed) {
    this.cursorSpeed = cursorSpeed;
  }

  public boolean isVirtualGamepad() {
    if (!elementsLoaded && !virtualGamepad) {
      File file = getProfileFile(context, id);
      if (file.isFile()) {
        try {
          String jsonStr = FileUtils.readString(file);
          JSONObject profileJSONObject = new JSONObject(jsonStr != null ? jsonStr : "{}");
          JSONArray elementsJSONArray = profileJSONObject.getJSONArray("elements");
          for (int i = 0; i < elementsJSONArray.length(); i++) {
            JSONObject elementJSONObject = elementsJSONArray.getJSONObject(i);
            JSONArray bindingsJSONArray = elementJSONObject.getJSONArray("bindings");
            boolean hasGamepadBinding = false;
            for (int j = 0; j < bindingsJSONArray.length(); j++) {
              Binding binding = Binding.fromString(bindingsJSONArray.getString(j));
              if (binding.isGamepad()) {
                hasGamepadBinding = true;
                break;
              }
            }
            if (hasGamepadBinding) {
              virtualGamepad = true;
              break;
            }
          }
        } catch (JSONException e) {
          e.printStackTrace();
        }
      }
    }
    return virtualGamepad;
  }

  public GamepadState getGamepadState() {
    if (gamepadState == null) gamepadState = new GamepadState();
    return gamepadState;
  }

  public ExternalController addController(String id) {
    ExternalController controller = getController(id);
    if (controller == null) {
      controller = ExternalController.getController(id);
      if (controller != null) controller.setContext(context);
      controllers.add(controller);
    }
    controllersLoaded = true;
    return controller;
  }

  public void removeController(ExternalController controller) {
    if (!controllersLoaded) loadControllers();
    controllers.remove(controller);
  }

  public void putController(ExternalController controller) {
    if (!controllersLoaded) loadControllers();
    for (int i = 0; i < controllers.size(); i++) {
      if (controllers.get(i).getId().equals(controller.getId())) {
        controllers.set(i, controller);
        controllersLoaded = true;
        return;
      }
    }
    controllers.add(controller);
    controllersLoaded = true;
  }

  public ExternalController getController(String id) {
    if (!controllersLoaded) loadControllers();
    for (ExternalController controller : controllers)
      if (controller.getId().equals(id)) return controller;
    return null;
  }

  public ExternalController getController(int deviceId) {
    if (!controllersLoaded) loadControllers();

    for (ExternalController controller : controllers) {
      if (controller.getDeviceId() == deviceId) return controller;
    }

    android.view.InputDevice device = android.view.InputDevice.getDevice(deviceId);
    if (device != null) {
      String descriptor = device.getDescriptor();
      String physicalId = ExternalController.getPhysicalDeviceIdentifier(device);
      for (ExternalController controller : controllers) {
        if (controller.getId().equals(physicalId) || controller.getId().equals(descriptor))
          return controller;
      }
    }

    return null;
  }

  @NonNull @Override
  public String toString() {
    return name;
  }

  @Override
  public int compareTo(ControlsProfile o) {
    return Integer.compare(id, o.id);
  }

  public boolean isElementsLoaded() {
    return elementsLoaded;
  }

  public void save() {
    File file = getProfileFile(context, id);

    try {
      JSONObject data = new JSONObject();
      data.put("id", id);
      data.put("name", name);
      data.put("cursorSpeed", Float.valueOf(cursorSpeed));

      JSONArray elementsJSONArray = new JSONArray();
      if (!elementsLoaded && file.isFile()) {
        JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
        elementsJSONArray = profileJSONObject.getJSONArray("elements");
      } else for (ControlElement element : elements) elementsJSONArray.put(element.toJSONObject());
      data.put("elements", elementsJSONArray);

      JSONArray controllersJSONArray = new JSONArray();
      if (!controllersLoaded && file.isFile()) {
        JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
        if (profileJSONObject.has("controllers"))
          controllersJSONArray = profileJSONObject.getJSONArray("controllers");
      } else {
        for (ExternalController controller : controllers) {
          JSONObject controllerJSONObject = controller.toJSONObject();
          if (controllerJSONObject != null) controllersJSONArray.put(controllerJSONObject);
        }
      }
      if (controllersJSONArray.length() > 0) data.put("controllers", controllersJSONArray);

      FileUtils.writeString(file, data.toString());
    } catch (JSONException e) {
    }
  }

  public static File getProfileFile(Context context, int id) {
    return new File(InputControlsManager.getProfilesDir(context), "controls-" + id + ".icp");
  }

  public synchronized void addElement(ControlElement element) {
    elements.add(element);
    elementsLoaded = true;
  }

  public synchronized void removeElement(ControlElement element) {
    elements.remove(element);
    elementsLoaded = true;
  }

  public List<ControlElement> getElements() {
    return Collections.unmodifiableList(elements);
  }

  public int getElementCountFromFile() {
    File file = getProfileFile(context, id);
    if (file.isFile()) {
      try {
        String jsonStr = FileUtils.readString(file);
        if (jsonStr == null) return 0;
        JSONObject obj = new JSONObject(jsonStr);
        if (obj.has("elements")) {
          return obj.getJSONArray("elements").length();
        }
      } catch (JSONException e) {
        e.printStackTrace();
      }
    }
    return 0;
  }

  public boolean isTemplate() {
    return name.toLowerCase(Locale.ENGLISH).contains("template");
  }

  public ArrayList<ExternalController> loadControllers() {
    controllers.clear();
    controllersLoaded = false;

    File file = getProfileFile(context, id);
    if (!file.isFile()) return controllers;

    try {
      String jsonStr = FileUtils.readString(file);
      if (jsonStr == null) return controllers;
      JSONObject profileJSONObject = new JSONObject(jsonStr);
      if (!profileJSONObject.has("controllers")) return controllers;
      JSONArray controllersJSONArray = profileJSONObject.getJSONArray("controllers");
      for (int i = 0; i < controllersJSONArray.length(); i++) {
        JSONObject controllerJSONObject = controllersJSONArray.getJSONObject(i);
        String id = controllerJSONObject.getString("id");
        ExternalController controller = new ExternalController();
        controller.setContext(context);
        controller.setId(id);
        controller.setName(controllerJSONObject.getString("name"));

        JSONArray controllerBindingsJSONArray =
            controllerJSONObject.getJSONArray("controllerBindings");
        for (int j = 0; j < controllerBindingsJSONArray.length(); j++) {
          JSONObject controllerBindingJSONObject = controllerBindingsJSONArray.getJSONObject(j);
          ExternalControllerBinding controllerBinding = new ExternalControllerBinding();
          controllerBinding.setKeyCode(controllerBindingJSONObject.getInt("keyCode"));
          controllerBinding.setBinding(
              Binding.fromString(controllerBindingJSONObject.getString("binding")));
          controller.addControllerBinding(controllerBinding);
        }
        controllers.add(controller);
      }
      controllersLoaded = true;
    } catch (JSONException e) {
      e.printStackTrace();
    }
    return controllers;
  }

  public void loadElements(InputControlsView inputControlsView) {
    File file = getProfileFile(context, id);
    if (!file.isFile()) {
      elements = new ArrayList<>();
      elementsLoaded = false;
      return;
    }

    try {
      String jsonStr = FileUtils.readString(file);
      if (jsonStr == null) return;
      JSONObject profileJSONObject = new JSONObject(jsonStr);
      JSONArray elementsJSONArray = profileJSONObject.getJSONArray("elements");
      ArrayList<ControlElement> tempElements = new ArrayList<>();
      boolean tempVirtualGamepad = false;

      for (int i = 0; i < elementsJSONArray.length(); i++) {
        JSONObject elementJSONObject = elementsJSONArray.getJSONObject(i);
        ControlElement element = new ControlElement(inputControlsView);
        element.setType(ControlElement.Type.valueOf(elementJSONObject.getString("type")));
        element.setShape(ControlElement.Shape.valueOf(elementJSONObject.getString("shape")));
        element.setToggleSwitch(elementJSONObject.getBoolean("toggleSwitch"));
        element.setSwipeable(elementJSONObject.optBoolean("swipeable", true));
        element.setX(
            (int) Math.round(elementJSONObject.getDouble("x") * inputControlsView.getMaxWidth()));
        element.setY(
            (int) Math.round(elementJSONObject.getDouble("y") * inputControlsView.getMaxHeight()));
        element.setScale((float) elementJSONObject.getDouble("scale"));
        element.setText(elementJSONObject.getString("text"));
        element.setIconId(elementJSONObject.getInt("iconId"));
        if (elementJSONObject.has("customColor"))
          element.setCustomColor(elementJSONObject.getInt("customColor"));
        if (elementJSONObject.has("range"))
          element.setRange(ControlElement.Range.valueOf(elementJSONObject.getString("range")));
        if (elementJSONObject.has("orientation"))
          element.setOrientation((byte) elementJSONObject.getInt("orientation"));
        if (elementJSONObject.has("opacity"))
          element.setOpacity((float) elementJSONObject.getDouble("opacity"));

        boolean hasGamepadBinding = false;
        JSONArray bindingsJSONArray = elementJSONObject.getJSONArray("bindings");
        element.setBindingCount(bindingsJSONArray.length());
        for (int j = 0; j < bindingsJSONArray.length(); j++) {
          Binding binding = Binding.fromString(bindingsJSONArray.getString(j));
          element.setBindingAt(j, Binding.fromString(bindingsJSONArray.getString(j)));
          if (binding.isGamepad()) hasGamepadBinding = true;
        }

        if (!tempVirtualGamepad && hasGamepadBinding) tempVirtualGamepad = true;
        tempElements.add(element);
      }

      synchronized (this) {
        this.elements = tempElements;
        this.virtualGamepad = tempVirtualGamepad;
        this.elementsLoaded = true;
      }
    } catch (JSONException e) {
      e.printStackTrace();
    }
  }
}
