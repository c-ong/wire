/*
 * Copyright 2013 Square Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.wire;

import com.squareup.javawriter.JavaWriter;
import com.squareup.protoparser.EnumType;
import com.squareup.protoparser.ExtendDeclaration;
import com.squareup.protoparser.MessageType;
import com.squareup.protoparser.ProtoFile;
import com.squareup.protoparser.Type;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeSet;

import static com.squareup.protoparser.MessageType.Field;
import static com.squareup.wire.Message.Datatype;
import static com.squareup.wire.Message.Label;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;

/** Compiler for Wire protocol buffers. */
public class WireCompiler {

  static final String INDENT = "  ";
  static final String LINE_WRAP_INDENT = "    ";

  private static final Charset ISO_8859_1 = Charset.forName("ISO_8859_1");
  private static final String PROTO_PATH_FLAG = "--proto_path=";
  private static final String JAVA_OUT_FLAG = "--java_out=";
  private static final String FILES_FLAG = "--files=";
  private static final String REGISTRY_CLASS_FLAG = "--registry_class=";
  private static final String ROOTS_FLAG = "--roots=";
  private static final String CODE_GENERATED_BY_WIRE =
      "Code generated by Wire protocol buffer compiler, do not edit.";

  private final String repoPath;
  private final List<String> sourceFileNames;
  private final IO io;
  private final Set<String> typesToEmit = new LinkedHashSet<String>();
  private final Map<String, String> javaSymbolMap = new LinkedHashMap<String, String>();
  private final Set<String> enumTypes = new LinkedHashSet<String>();
  private final Map<String, String> enumDefaults = new LinkedHashMap<String, String>();
  private final Map<String, ExtensionInfo> extensionInfo =
      new LinkedHashMap<String, ExtensionInfo>();
  private final Map<String, FieldInfo> fieldMap = new LinkedHashMap<String, FieldInfo>();
  private final String outputDirectory;
  private final String registryClass;
  private final List<String> extensionClasses = new ArrayList<String>();
  private final MessageOptionsMapMaker messageOptionsMapMaker = new MessageOptionsMapMaker(this);

  private ProtoFile protoFile;
  private JavaWriter writer;
  private String sourceFileName;
  private String protoFileName;
  private String typeBeingGenerated = "";

  /**
   * Runs the compiler. Usage:
   *
   * <pre>
   * java WireCompiler --proto_path=<path> --java_out=<path> [--files=<protos.include>]
   *     [--roots=<message_name>[,<message_name>...]] [--registry_class=<class_name>]
   *     [file [file...]]
   * </pre>
   *
   * If the {@code --roots} flag is present, its argument must be a comma-separated list
   * of fully-qualified message or enum names. The output will be limited to those messages
   * and enums that are (transitive) dependencies of the listed names.
   * <p>
   * If the {@code --registry_class} flag is present, its argument must be a Java class name. A
   * class with the given name will be generated, containing a constant list of all extension
   * classes generated during the compile. This list is suitable for passing to Wire's constructor
   * at runtime for constructing its internal extension registry.
   *
   */
  public static void main(String... args) throws Exception {
    String protoPath = null;
    String javaOut = null;
    String registryClass = null;
    List<String> sourceFileNames = new ArrayList<String>();
    List<String> roots = new ArrayList<String>();

    int index = 0;
    while (index < args.length) {
      if (args[index].startsWith(PROTO_PATH_FLAG)) {
        protoPath = args[index].substring(PROTO_PATH_FLAG.length());
      } else if (args[index].startsWith(JAVA_OUT_FLAG)) {
        javaOut = args[index].substring(JAVA_OUT_FLAG.length());
      } else if (args[index].startsWith(FILES_FLAG)) {
        File files = new File(args[index].substring(FILES_FLAG.length()));
        String[] fileNames = new Scanner(files, "UTF-8").useDelimiter("\\A").next().split("\n");
        sourceFileNames.addAll(Arrays.asList(fileNames));
      } else if (args[index].startsWith(ROOTS_FLAG)) {
        roots.addAll(Arrays.asList(args[index].substring(ROOTS_FLAG.length()).split(",")));
      } else if (args[index].startsWith(REGISTRY_CLASS_FLAG)) {
        registryClass = args[index].substring(REGISTRY_CLASS_FLAG.length());
      } else {
        sourceFileNames.add(args[index]);
      }
      index++;
    }
    if (javaOut == null) {
      System.err.println("Must specify " + JAVA_OUT_FLAG + " flag");
      System.exit(1);
    }
    if (protoPath == null) {
      protoPath = System.getProperty("user.dir");
      System.err.println(PROTO_PATH_FLAG + " flag not specified, using current dir " + protoPath);
    }
    WireCompiler wireCompiler =
        new WireCompiler(protoPath, sourceFileNames, roots, javaOut, registryClass);
    wireCompiler.compile();
  }

  public WireCompiler(String protoPath, List<String> sourceFileNames, List<String> roots,
      String outputDirectory, String registryClass) {
    this(protoPath, sourceFileNames, roots, outputDirectory, registryClass, new IO.FileIO());
  }

   WireCompiler(String protoPath, List<String> sourceFileNames, List<String> roots,
      String outputDirectory, String registryClass, IO io) {
    this.repoPath = protoPath;
    this.typesToEmit.addAll(roots);
    this.sourceFileNames = sourceFileNames;
    this.outputDirectory = outputDirectory;
    this.registryClass = registryClass;
    this.io = io;
  }

  public void compile() throws IOException {
    Map<String, ProtoFile> parsedFiles = new LinkedHashMap<String, ProtoFile>();

    for (String sourceFilename : sourceFileNames) {
      String sourcePath = repoPath + File.separator + sourceFilename;
      ProtoFile protoFile = io.parse(sourcePath);
      parsedFiles.put(sourcePath, protoFile);

      loadSymbols(protoFile);
    }

    if (!typesToEmit.isEmpty()) {
      System.out.println("Analyzing dependencies of root types.");
      findDependencies(parsedFiles.values());
    }

    for (Map.Entry<String, ProtoFile> entry : parsedFiles.entrySet()) {
      this.sourceFileName = entry.getKey();
      this.protoFile = entry.getValue();
      this.protoFileName = protoFileName(protoFile.getFileName());
      System.out.println("Compiling proto source file " + sourceFileName);
      compileOne();
    }

    if (registryClass != null) {
      emitRegistry();
    }
  }
  ProtoFile getProtoFile() {
    return protoFile;
  }

  MessageOptionsMapMaker getMessageOptionsMapMaker() {
    return messageOptionsMapMaker;
  }

  JavaWriter getWriter() {
    return writer;
  }

  String getEnumDefault(String fullyQualifiedName) {
    return enumDefaults.get(fullyQualifiedName);
  }

  FieldInfo getField(String dollarName) {
    return fieldMap.get(dollarName);
  }

  String javaName(ProtoFile protoFile, MessageType messageType, String type) {
    String scalarType = TypeInfo.scalarType(type);
    return scalarType != null
        ? scalarType : shortenJavaName(protoFile,
        javaName(fullyQualifiedName(protoFile, messageType, type)));
  }

  boolean fullyQualifiedNameIsOutsidePackage(String fqName) {
    return fqName != null
        && !protoFile.getJavaPackage().equals(getPackageFromFullyQualifiedJavaName(fqName));
  }

  String prefixWithPackageName(String name) {
    return prefixWithPackageName(protoFile, name);
  }

  boolean hasFields(Type type) {
    return type instanceof MessageType && !((MessageType) type).getFields().isEmpty();
  }

  boolean hasExtensions(MessageType messageType) {
    return !messageType.getExtensions().isEmpty();
  }

  String getTrailingSegment(String name) {
    int index = name.lastIndexOf('.');
    return index == -1 ? name : name.substring(index + 1);
  }

  ExtensionInfo getExtension(String name) {
    return extensionInfo.get(name);
  }

  String getInitializerForType(String initialValue, String javaTypeName) {
    if ("Boolean".equals(javaTypeName)) {
      return initialValue == null ? "false" : initialValue;
    } else if ("Integer".equals(javaTypeName)) {
      // Wrap unsigned values
      return initialValue == null ? "0" : toInt(initialValue);
    } else if ("Long".equals(javaTypeName)) {
      // Wrap unsigned values and add an 'L'
      return initialValue == null ? "0L" : toLong(initialValue) + "L";
    } else if ("Float".equals(javaTypeName)) {
      return initialValue == null ? "0F" : initialValue + "F";
    } else if ("Double".equals(javaTypeName)) {
      return initialValue == null ? "0D" : initialValue + "D";
    } else if ("String".equals(javaTypeName)) {
      return quoteString(initialValue);
    } else if ("ByteString".equals(javaTypeName)) {
      if (initialValue == null) {
        return "ByteString.EMPTY";
      } else {
        return "ByteString.of(\"" + Stringer.encode(initialValue.getBytes(ISO_8859_1)) + "\")";
      }
    } else {
      throw new IllegalArgumentException(javaTypeName + " is not an allowed scalar type");
    }
  }

  /**
   * Returns true if the given fully-qualified name (with a .proto package name)
   * refers to an .proto enumerated type.
   */
  boolean isEnum(String type) {
    return enumTypes.contains(type);
  }

  String javaName(MessageType messageType, String type) {
    String scalarType = TypeInfo.scalarType(type);
    return scalarType != null
        ? scalarType : shortenJavaName(javaName(fullyQualifiedName(messageType, type)));
  }

  String javaName(String fqName) {
    return javaSymbolMap.get(fqName);
  }

  String fullyQualifiedName(MessageType messageType, String type) {
    return fullyQualifiedName(protoFile, messageType, type);
  }

  String shortenJavaName(String fullyQualifiedName) {
    return shortenJavaName(protoFile, fullyQualifiedName);
  }

  private void compileOne() throws IOException {
    typeBeingGenerated = "";

    if (hasExtends()) {
      try {
        String className = "Ext_" + protoFileName;
        String javaPackage = protoFile.getJavaPackage();
        writer = io.getJavaWriter(outputDirectory, javaPackage, className);
        emitExtensionClass();

        String extensionClass = javaPackage + "." + className;
        System.out.println("wrote extension class " + extensionClass);
        extensionClasses.add(extensionClass);
      } finally {
        writer.close();
      }
    }

    for (Type type : protoFile.getTypes()) {
      if (shouldEmitType(type.getFullyQualifiedName())) {
        String savedType = typeBeingGenerated;
        typeBeingGenerated += type.getName() + ".";
        emitMessageClass(type);
        typeBeingGenerated = savedType;
      }
    }
  }

  private boolean hasMessageOption(List<Type> types) {
    for (Type type : types) {
      if (type instanceof MessageType && !((MessageType) type).getOptions().isEmpty()) {
        return true;
      }
    }
    return false;
  }

  private void getTypes(Type parent, List<Type> types) {
    types.add(parent);
    for (Type nestedType : parent.getNestedTypes()) {
      getTypes(nestedType, types);
    }
  }

  private void emitRegistry() throws IOException {
    int packageClassSep = registryClass.lastIndexOf(".");
    String javaPackage = registryClass.substring(0, packageClassSep);
    String className = registryClass.substring(packageClassSep + 1);
    try {
      writer = io.getJavaWriter(outputDirectory, javaPackage, className);
      writer.emitSingleLineComment(CODE_GENERATED_BY_WIRE);
      writer.emitPackage(javaPackage);

      writer.emitImports("java.util.List");
      writer.emitEmptyLine();
      writer.emitStaticImports("java.util.Arrays.asList", "java.util.Collections.unmodifiableList");
      writer.emitEmptyLine();
      writer.beginType(className, "class", EnumSet.of(PUBLIC, FINAL));
      writer.emitEmptyLine();

      StringBuilder classes = new StringBuilder("unmodifiableList(asList(\n");
      int extensionsCount = extensionClasses.size();
      for (int i = 0; i < extensionsCount; i++) {
        String format = (i < extensionsCount - 1) ? "%1$s%2$s.class,%n" : "%1$s%2$s.class";
        String extensionClass = extensionClasses.get(i);
        classes.append(String.format(format, INDENT + LINE_WRAP_INDENT, extensionClass));
      }
      classes.append("))");
      writer.emitField("List<Class<?>>", "EXTENSIONS", EnumSet.of(PUBLIC, STATIC, FINAL),
          classes.toString());
      writer.emitEmptyLine();

      // Private no-args constructor
      writer.beginMethod(null, className, EnumSet.of(PRIVATE));
      writer.endMethod();
      writer.endType();
    } finally {
      writer.close();
    }
  }

  private boolean shouldEmitType(String name) {
    return typesToEmit.isEmpty() || typesToEmit.contains(name);
  }

  private void findDependencies(Collection<ProtoFile> protoFiles) throws IOException {
    Set<String> loadedDependencies = new LinkedHashSet<String>();
    int count = typesToEmit.size();
    while (true) {
      for (ProtoFile protoFile : protoFiles) {
        findDependenciesHelper(protoFile, loadedDependencies);
      }
      int newCount = typesToEmit.size();
      if (newCount == count) {
        break;
      }
      count = newCount;
    }
  }

  private void findDependenciesHelper(ProtoFile protoFile, Set<String> loadedDependencies)
      throws IOException {
    // Load symbols from imports
    for (String dependency : protoFile.getDependencies()) {
      if (!loadedDependencies.contains(dependency)) {
        String dep = repoPath + File.separator + dependency;
        ProtoFile dependencyFile = io.parse(dep);
        loadSymbols(dependencyFile);
        loadedDependencies.add(dependency);
      }
    }

    for (ExtendDeclaration extend : protoFile.getExtendDeclarations()) {
      String typeName = extend.getFullyQualifiedName();
      typesToEmit.add(typeName);
      for (Field field : extend.getFields()) {
        // FIXME: we need to determine the fully-qualified name of the extension field.
        // Perhaps this should be handled by protoparser.
        // For now, just prepend the proto file's package name
        String fieldTypeName = prefixWithPackageName(protoFile, field.getType());
        typesToEmit.add(fieldTypeName);
      }
    }

    addDependencies(protoFile.getTypes(), protoFile.getJavaPackage() + ".");
  }

  /** Expands the set of types to emit to include types of fields of current emittable types. */
  private void addDependencies(List<Type> types, String javaPrefix) {
    for (Type type : types) {
      String name = type.getName();
      String fqName = type.getFullyQualifiedName();
      if (type instanceof MessageType && typesToEmit.contains(fqName)) {
        for (MessageType.Field field : ((MessageType) type).getFields()) {
          String fieldType = field.getType();
          if (!TypeInfo.isScalar(fieldType)) {
            String fqFieldType = fullyQualifiedName((MessageType) type, field.getType());
            addDependencyBranch(fqFieldType);
          }
        }
      }
      addDependencies(type.getNestedTypes(), javaPrefix + name + ".");
    }
  }

  /** Adds a type name and all its ancestors to the set of emittable types. */
  private void addDependencyBranch(String name) {
    while (typeIsComplete(name)) {
      typesToEmit.add(name);
      name = removeTrailingSegment(name);
    }
  }

  private String removeTrailingSegment(String name) {
    int index = name.lastIndexOf('.');
    return index == -1 ? "" :  name.substring(0, index);
  }

  private enum LoadSymbolsPass {
    LOAD_TYPES, LOAD_FIELDS
  }

  private void loadSymbols(ProtoFile protoFile) throws IOException {
    // Make two passes through the input files. In the first pass we collect message and enum
    // types, and in the second pass we collect field types.
    loadSymbolsHelper(protoFile, new LinkedHashSet<String>(), LoadSymbolsPass.LOAD_TYPES);
    loadSymbolsHelper(protoFile, new LinkedHashSet<String>(), LoadSymbolsPass.LOAD_FIELDS);
  }

  // Call with pass == LOAD_TYPES, then pass == LOAD_FIELDS
  private void loadSymbolsHelper(ProtoFile protoFile, Set<String> loadedDependencies,
      LoadSymbolsPass pass) throws IOException {
    // Load symbols from imports
    for (String dependency : protoFile.getDependencies()) {
      if (!loadedDependencies.contains(dependency)) {
        String dep = repoPath + File.separator + dependency;
        ProtoFile dependencyFile = io.parse(dep);
        loadSymbolsHelper(dependencyFile, loadedDependencies, pass);
        loadedDependencies.add(dependency);
      }
    }

    addTypes(protoFile.getTypes(), protoFile.getJavaPackage() + ".", pass);
    addExtensions(protoFile);
  }

  private void addExtensions(ProtoFile protoFile) {
    for (ExtendDeclaration extend : protoFile.getExtendDeclarations()) {
      for (MessageType.Field field : extend.getFields()) {
        String fieldType = field.getType();
        String type = javaName(protoFile, null, fieldType);
        if (type == null) {
          type = javaName(protoFile, null, prefixWithPackageName(protoFile, fieldType));
        }
        type = shortenJavaName(protoFile, type);
        String fqName = prefixWithPackageName(protoFile, field.getName());
        String fqType;

        boolean isScalar = TypeInfo.isScalar(fieldType);
        boolean isEnum = !isScalar && isEnum(fullyQualifiedName(protoFile, null, fieldType));
        if (isScalar) {
          type = field.getType();
          fqType = type;
        } else if (isEnum) {
          // Store fully-qualified name for enumerations so we can identify them later
          type = fullyQualifiedName(protoFile, null, fieldType);
          fqType = type;
        } else {
          fqType = fullyQualifiedName(protoFile, null, fieldType);
        }

        String location = protoFileName(protoFile.getFileName());
        String fqLocation = protoFile.getJavaPackage() + ".Ext_" + location;
        ExtensionInfo info = new ExtensionInfo(type, fqType, location, fqLocation,
            field.getLabel());
        extensionInfo.put(fqName, info);
      }
    }
  }

  private void addTypes(List<Type> types, String javaPrefix, LoadSymbolsPass pass) {
    for (Type type : types) {
      String name = type.getName();
      if (pass == LoadSymbolsPass.LOAD_TYPES) {
        String fqName = type.getFullyQualifiedName();
        javaSymbolMap.put(fqName, javaPrefix + name);
        if (type instanceof EnumType) {
          enumTypes.add(fqName);
          enumDefaults.put(fqName, ((EnumType) type).getValues().get(0).getName());
        }
      } else if (type instanceof MessageType) {
        addFields((MessageType) type);
      }
      addTypes(type.getNestedTypes(), javaPrefix + name + ".", pass);
    }
  }

  private void addFields(MessageType messageType) {
    for (MessageType.Field field : messageType.getFields()) {
      String fieldType = field.getType();
      String fqMessageName = messageType.getFullyQualifiedName();
      String key = fqMessageName + "$" + field.getName();
      fieldMap.put(key, new FieldInfo(TypeInfo.isScalar(fieldType)
          ? fieldType : fullyQualifiedName(messageType, fieldType), field.getLabel()));
    }
  }

  private String fullyQualifiedName(ProtoFile protoFile, MessageType messageType, String type) {
    if (typeIsComplete(type)) {
      return type;
    } else {
      String prefix = messageType == null
          ? protoFile.getPackageName() : messageType.getFullyQualifiedName();
      while (!prefix.isEmpty()) {
        String fqname = prefix + "." + type;
        if (typeIsComplete(fqname)) return fqname;
        prefix = removeTrailingSegment(prefix);
      }
    }
    throw new RuntimeException("Unknown type " + type + " in message "
        + (messageType == null ? "<unknown>" : messageType.getName()));
  }


  private String shortenJavaName(ProtoFile protoFile, String fullyQualifiedName) {
    if (fullyQualifiedName == null) return null;
    String javaTypeBeingGenerated = protoFile.getJavaPackage() + "." + typeBeingGenerated;
    if (fullyQualifiedName.startsWith(javaTypeBeingGenerated)) {
      return fullyQualifiedName.substring(javaTypeBeingGenerated.length());
    }

    // Dependencies in javaSymbolMap are already imported.
    for (String javaSymbol : javaSymbolMap.values()) {
      if (fullyQualifiedName.startsWith(javaSymbol)) {
        // omit package part
        String pkgPrefix = getPackageFromFullyQualifiedJavaName(fullyQualifiedName) + '.';
        return fullyQualifiedName.substring(pkgPrefix.length());
      }
    }

    return fullyQualifiedName;
  }

  private String protoFileName(String path) {
    int slashIndex = path.lastIndexOf('/');
    if (slashIndex != -1) {
      path = path.substring(slashIndex + 1);
    }
    if (path.endsWith(".proto")) {
      path = path.substring(0, path.length() - ".proto".length());
    }
    return path;
  }

  private void emitMessageClass(Type type) throws IOException {
    try {
      writer = io.getJavaWriter(outputDirectory, protoFile.getJavaPackage(), type.getName());
      writer.emitSingleLineComment(CODE_GENERATED_BY_WIRE);
      writer.emitSingleLineComment("Source file: %s", sourceFileName);
      writer.emitPackage(protoFile.getJavaPackage());

      List<Type> types = new ArrayList<Type>();
      getTypes(type, types);
      boolean hasMessage = hasMessage(types);
      boolean hasExtensions = hasExtensions(Arrays.asList(type));

      Set<String> imports = new LinkedHashSet<String>();
      if (hasMessage) {
        imports.add("com.squareup.wire.Message");
      }
      if (hasMessage || hasExtensions) {
        if (hasFields(type)) {
          imports.add("com.squareup.wire.ProtoField");
        }
      }
      if (hasBytesField(types)) {
        imports.add("com.squareup.wire.ByteString");
      }
      if (hasEnum(types)) {
        imports.add("com.squareup.wire.ProtoEnum");
      }
      if (hasRepeatedField(types)) {
        imports.add("java.util.Collections");
        imports.add("java.util.List");
      }
      if (hasExtensions) {
        imports.add("com.squareup.wire.ExtendableMessage");
        imports.add("com.squareup.wire.Extension");
      }
      if (hasMessageOption(types)) {
        imports.add("com.google.protobuf.MessageOptions");
      }

      List<String> externalTypes = new ArrayList<String>();
      getExternalTypes(type, externalTypes);

      Map<String, ?> optionsMap = null;
      if (type instanceof MessageType) {
        optionsMap = messageOptionsMapMaker.createOptionsMap((MessageType) type);
      }
      if (optionsMap != null) {
        messageOptionsMapMaker.getOptionTypes(optionsMap, externalTypes);
      }
      imports.addAll(externalTypes);

      // Emit static imports for Datatype. and Label. enums
      Collection<Datatype> datatypes = new TreeSet<Datatype>(Datatype.ORDER_BY_NAME);
      Collection<Label> labels = new TreeSet<Label>(Label.ORDER_BY_NAME);
      getDatatypesAndLabels(type, datatypes, labels);
      // No need to emit 'label = OPTIONAL' since it is the default
      labels.remove(Label.OPTIONAL);

      MessageWriter messageWriter = new MessageWriter(this);
      messageWriter.emitHeader(imports, datatypes, labels);
      messageWriter.emitType(type, protoFile.getPackageName() + ".", optionsMap, true);
    } finally {
      writer.close();
    }
  }

  private void getExternalTypes(Type parent, List<String> types) {
    if (parent instanceof MessageType) {
      MessageType messageType = (MessageType) parent;
      for (Field field : messageType.getFields()) {
        String fqName = fullyQualifiedJavaName(messageType, field.getType());
        if (fullyQualifiedNameIsOutsidePackage(fqName)) {
          types.add(fqName);
        }
      }
    }
    for (Type nestedType : parent.getNestedTypes()) {
      getExternalTypes(nestedType, types);
    }
  }

  private String getPackageFromFullyQualifiedJavaName(String fqName) {
    while (javaSymbolMap.containsValue(fqName)) {
      fqName = removeTrailingSegment(fqName);
    }
    return fqName;
  }

  private List<String> getExtensionTypes() {
    List<String> extensionClasses = new ArrayList<String>();
    for (ExtendDeclaration extend : protoFile.getExtendDeclarations()) {
      String fqName = fullyQualifiedJavaName(null, extend.getFullyQualifiedName());
      if (fullyQualifiedNameIsOutsidePackage(fqName)) {
        extensionClasses.add(fqName);
      }
      for (Field field : extend.getFields()) {
        String fqFieldType = fullyQualifiedJavaName(null, field.getType());
        if (fullyQualifiedNameIsOutsidePackage(fqFieldType)) {
          extensionClasses.add(fqFieldType);
        }
      }
    }
    return extensionClasses;
  }

  private boolean hasExtends() {
    return !protoFile.getExtendDeclarations().isEmpty();
  }

  private void emitExtensionClass() throws IOException {
    writer.emitSingleLineComment(CODE_GENERATED_BY_WIRE);
    writer.emitSingleLineComment("Source file: %s", sourceFileName);
    writer.emitPackage(protoFile.getJavaPackage());

    Set<String> imports = new LinkedHashSet<String>();
    if (hasByteStringExtension()) {
      imports.add("com.squareup.wire.ByteString");
    }
    imports.add("com.squareup.wire.Extension");
    if (hasRepeatedExtension()) {
      imports.add("java.util.List");
    }
    imports.addAll(getExtensionTypes());
    writer.emitImports(imports);
    writer.emitEmptyLine();

    String className = "Ext_" + protoFileName;
    writer.beginType(className, "class", EnumSet.of(PUBLIC, FINAL));
    writer.emitEmptyLine();

    // Private no-args constructor
    writer.beginMethod(null, className, EnumSet.of(PRIVATE));
    writer.endMethod();
    writer.emitEmptyLine();

    emitExtensions();
    writer.endType();
  }

  private void emitExtensions() throws IOException {
    for (ExtendDeclaration extend : protoFile.getExtendDeclarations()) {
      String fullyQualifiedName = extend.getFullyQualifiedName();
      String javaName = javaName(null, fullyQualifiedName);
      String name = shortenJavaName(javaName);
      for (MessageType.Field field : extend.getFields()) {
        String fieldType = field.getType();
        String type = javaName(null, fieldType);
        if (type == null) {
          type = javaName(null, prefixWithPackageName(fieldType));
        }
        type = shortenJavaName(type);
        String initialValue;
        String className = writer.compressType(name);
        String extensionName = field.getName();
        String fqName = prefixWithPackageName(field.getName());
        int tag = field.getTag();

        boolean isScalar = TypeInfo.isScalar(fieldType);
        boolean isEnum = !isScalar && isEnum(fullyQualifiedName(null, fieldType));
        String labelString = getLabelString(field, isEnum);
        if (isScalar) {
          initialValue = String.format("Extension%n"
              + "%1$s.%2$sExtending(%3$s.class)%n"
              + "%1$s.setName(\"%4$s\")%n"
              + "%1$s.setTag(%5$d)%n"
              + "%1$s.build%6$s()", INDENT + LINE_WRAP_INDENT, field.getType(), className, fqName,
              tag, labelString);
        } else if (isEnum) {
          initialValue = String.format("Extension%n"
              + "%1$s.enumExtending(%2$s.class, %3$s.class)%n"
              + "%1$s.setName(\"%4$s\")%n"
              + "%1$s.setTag(%5$d)%n"
              + "%1$s.build%6$s()", INDENT + LINE_WRAP_INDENT, type, className, fqName, tag,
              labelString);
        } else {
          initialValue = String.format("Extension%n"
              + "%1$s.messageExtending(%2$s.class, %3$s.class)%n"
              + "%1$s.setName(\"%4$s\")%n"
              + "%1$s.setTag(%5$d)%n"
              + "%1$s.build%6$s()", INDENT + LINE_WRAP_INDENT, type, className, fqName, tag,
              labelString);
        }

        if (FieldInfo.isRepeated(field)) {
          type = "List<" + type + ">";
        }
        writer.emitField("Extension<" + name + ", " + type + ">", extensionName,
            EnumSet.of(PUBLIC, STATIC, FINAL), initialValue);
      }
    }
  }

  private String prefixWithPackageName(ProtoFile protoFile, String name) {
    return protoFile.getPackageName() + "." + name;
  }

  private String getLabelString(Field field, boolean isEnum) {
    switch (field.getLabel()) {
      case OPTIONAL: return "Optional";
      case REQUIRED: return "Required";
      case REPEATED:
        return FieldInfo.isPacked(field, isEnum) ? "Packed" : "Repeated";
      default:
        throw new RuntimeException("Unknown extension label \"" + field.getLabel() + "\"");
    }
  }

  private boolean hasByteStringExtension() {
    for (ExtendDeclaration extend : protoFile.getExtendDeclarations()) {
      for (MessageType.Field field : extend.getFields()) {
        String fieldType = field.getType();
        if ("bytes".equals(fieldType)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean hasRepeatedExtension() {
    for (ExtendDeclaration extend : protoFile.getExtendDeclarations()) {
      for (MessageType.Field field : extend.getFields()) {
        if (field.getLabel() == MessageType.Label.REPEATED) {
          return true;
        }
      }
    }
    return false;
  }

  private String toInt(String value) {
    return Integer.toString(new BigDecimal(value).intValue());
  }

  private String toLong(String value) {
    return Long.toString(new BigDecimal(value).longValue());
  }

  private String quoteString(String initialValue) {
    return initialValue == null ? "\"\"" : JavaWriter.stringLiteral(initialValue);
  }

  private boolean hasEnum(List<Type> types) {
    for (Type type : types) {
      if (type instanceof EnumType || hasEnum(type.getNestedTypes())) return true;
    }
    return false;
  }

  private boolean hasExtensions(List<Type> types) {
    for (Type type : types) {
      if (type instanceof MessageType && hasExtensions(((MessageType) type))) return true;
      if (hasExtensions(type.getNestedTypes())) return true;
    }
    return false;
  }

  private boolean hasMessage(List<Type> types) {
    for (Type type : types) {
      if (type instanceof MessageType && !hasExtensions(((MessageType) type))) return true;
      if (hasMessage(type.getNestedTypes())) return true;
    }
    return false;
  }

  private boolean hasRepeatedField(List<Type> types) {
    for (Type type : types) {
      if (type instanceof MessageType) {
        for (Field field : ((MessageType) type).getFields()) {
          if (FieldInfo.isRepeated(field)) return true;
        }
      }
      if (hasRepeatedField(type.getNestedTypes())) return true;
    }
    return false;
  }

  private boolean hasBytesField(List<Type> types) {
    for (Type type : types) {
      if (type instanceof MessageType) {
        for (Field field : ((MessageType) type).getFields()) {
          if ("bytes".equals(field.getType())) return true;
        }
      }
      if (hasBytesField(type.getNestedTypes())) return true;
    }
    return false;
  }

  private void getDatatypesAndLabels(Type type, Collection<Datatype> types,
      Collection<Label> labels) {
    if (type instanceof MessageType) {
      for (MessageType.Field field : ((MessageType) type).getFields()) {
        String fieldType = field.getType();
        Datatype datatype = Datatype.of(fieldType);
        // If not scalar, determine whether it is an enum
        if (datatype == null && isEnum(fullyQualifiedName((MessageType) type, field.getType()))) {
          datatype = Datatype.ENUM;
        }
        if (datatype != null) types.add(datatype);

        // Convert Protoparser label to Wire label
        MessageType.Label label = field.getLabel();
        switch (label) {
          case OPTIONAL:
            labels.add(Label.OPTIONAL);
            break;
          case REQUIRED:
            labels.add(Label.REQUIRED);
            break;
          case REPEATED:
            if (FieldInfo.isPacked(field, false)) {
              labels.add(Label.PACKED);
            } else {
              labels.add(Label.REPEATED);
            }
            break;
          default:
            throw new AssertionError("Unknown label " + label);
        }
      }

      for (Type nestedType : type.getNestedTypes()) {
        getDatatypesAndLabels(nestedType, types, labels);
      }
    }
  }

  private boolean typeIsComplete(String type) {
    return javaSymbolMap.containsKey(type);
  }

  private String fullyQualifiedJavaName(MessageType messageType, String type) {
    return TypeInfo.isScalar(type) ? null : javaName(fullyQualifiedName(messageType, type));
  }
}
