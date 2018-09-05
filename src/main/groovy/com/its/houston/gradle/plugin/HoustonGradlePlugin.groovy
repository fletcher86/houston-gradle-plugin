package com.its.houston.gradle.plugin

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.bundling.Zip
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.jar.JarEntry
import java.util.jar.JarFile
import org.gradle.api.logging.LogLevel

/**
 * <code>HoustonGradlePlugin</code>
 * <p/>
 * A custom Gradle Plugin that adds Houston specific build Tasks and performs other build configuration activity.
 * <p/>
 * NOTE: The build.gradle from which this Plugin will be used must contain the dependency configuration 'houston' as
 * it will be used to add compile and runtime dependencies.
 * <p />
 */
class HoustonGradlePlugin
implements Plugin<Project>
{
  // Directory where Houston distro will be available, relative to the PROJECT_ROOT_DIR/build directory

  private static Logger sLogger = LoggerFactory.getLogger( HoustonGradlePlugin.class.name )

  private Map<String, Integer> mBundleStartLevelMap = new LinkedHashMap<String, Integer>()
  private def mHoustonHomeDir

  /**
   * Main entry point. This is called by the Gradle framework when this custom Plugin is used in a build script.
   * <p />
   * @param project - Reference to the Gradle Project object from where this Plugin is used
   */
  void apply( final Project project )
  {
    if ( (mHoustonHomeDir = System.getenv( 'HOUSTON_HOME' )) == null )
    {
      throw new IllegalStateException( "Couldn't find the mandatory HOUSTON_HOME system variable; please set it. " +
        "E.g: export HOUSTON_HOME=[/home/someDirectory], all Houston artifacts will be created in [/home/someDirectory]" )
    }

    if ( project.configurations.getByName( 'houston' ) == null )
    {
      throw new IllegalStateException( "Couldn't configure the Houston custom Gradle Plugin; your Project's build.gradle " +
        "doesn't have a configuration named 'houston'" )
    }

    project.afterEvaluate() {
      addHoustonCompileAndRuntimeDependenciesForCurrentProject( project )
      addUnzipHoustonTemplateTaskToProject( project )
      addBundorTask( project )
      addCopyToHoustonTask( project )
      addProtostuffCompileTask( project )
      addZipHoustonTask( project )
    }
  }

  /**
   * Parse the HOUSTON_HOME/conf/houston-bundles.ini file that contains all known Houston compile and runtime bundles, construct a
   * dependency specification String in the form of "groupId:name:version@jar" and add it to the current project's dependency
   * configuration 'houston'.
   * <p />
   * NOTE: The current Project must have the 'houston' dependency configuration defined
   * <p />
   * @param project - Reference to the Gradle Project object from where this Plugin is used
   */
  def addHoustonCompileAndRuntimeDependenciesForCurrentProject( Project project )
  {
    try
    {
      // Lil bit of hackery to be able to get a reference to the plugin's jar file to be able to read the ini file
      File depFile
      project.configurations.create( "tmp" )
      project.dependencies.add( 'tmp', "com.its.houston:com.its.houston-gradleplugin:${project.property( 'houston.gradleplugin.version' )}@jar" )
      project.configurations.getByName( "tmp" ).each { dependency ->
        if ( dependency.name.contains( "gradleplugin" ) )
        {
          depFile = dependency
        }
      }
      JarFile jarFile = new JarFile( "${depFile.canonicalPath}" )
      JarEntry entry = jarFile.getJarEntry( "conf/houston-bundles.ini" )
      BufferedReader reader = new BufferedReader( new InputStreamReader( jarFile.getInputStream( entry ) ) )

      // Parse the file
      reader.eachLine { String line ->
        if ( line.contains( "#" ) || line.equals( "" ) || line.length() < 5 )
        {
          return
        }
        if ( project.name.equals( "kernel" ) && line.contains( "kernel" ) )
        {
          // Skip the Kernel module, no other module/component should have any compile time dependency on Kernel
          return
        }
        String[] tokens = line.split( ":" )
        if ( tokens.length < 7 )
        {
          throw new IllegalArgumentException( "Couldn't parse the: [${mHoustonHomeDir}/conf/houston-bundles.ini], " +
            "line: [${line}] format is incorrect" )
        }
        def dependency = "${tokens[0]}:${tokens[1]}:${tokens[2]}@jar"
        sLogger.info( "*** Adding: ${dependency.trim()} as a compile & runtime dependency ..." )
        project.dependencies.add( 'houston', dependency.trim() )
        Integer startLevel = Integer.parseInt( tokens[4] )
        String bundleName = "${tokens[1]}-${tokens[2]}.jar"
        if ( startLevel >= 0 && startLevel < 5 )
        {
          mBundleStartLevelMap.put( bundleName, startLevel )
        }
        else
        {
          throw new IllegalArgumentException( "Invalid bundle start level: [${startLevel}], vaild values are: 0, 1, 3, and 6" )
        }
      }
    }
    catch ( Throwable e )
    {
      throw new IllegalStateException( "Couldn't set Houston compile and runtime dependencies in the 'houston' configuration of the project", e )
    }
    sLogger.info( "*** Set Houston compile and runtime dependencies in the 'houston' configuration of the project ..." )
  }

  /**
   * Add Tasks "copyLibs, unzipHoustonTemplate, deployLocal" to the current Project. These Tasks does the following:
   * <ol>
   *   <li>Delete the HOUSTON_HOME directory first, if it exists</li>
   *   <li>Copy the Gradle Plugin jar to the current projects build/libs directory</li>
   *   <li>Unjar the Gradle plugin jar to the HOUSTON_HOME directory</li>
   * </ol>
   * <p />
   * The content in the HOUSTON_HOME will be used to create the master Zip file that represents a Houston distro
   * <p />
   * @param project - Represents the current Project
   */
  def addUnzipHoustonTemplateTaskToProject( Project project )
  {
    try
    {
      // Delete the HOUSTON_HOME directory first, if it exists
      project.task( name: 'deleteHoustonDir', type: Delete ) {
        delete "${mHoustonHomeDir}"
      }
	  

      // Copy the Gradle Plugin jar to the build/libs directory
      project.task( name: 'copyLibs', type: Copy, dependsOn: 'deleteHoustonDir' ) {
        from project.configurations.houston
        include '*gradleplugin*'
        into "${project.libsDir}"
      }

      // Unjar Gradle plugin jar to HOUSTON_HOME directory
      project.task( name: 'unzipHoustonTemplate', type: Copy, dependsOn: 'copyLibs' ) {
        from project.zipTree( "${project.libsDir}/com.its.houston-gradleplugin-${project.property( 'houston.gradleplugin.version' )}.jar" )
        include 'conf/**'
        include 'bin/**'
        include 'bundles/**'
        include 'jetty/**'
        into "${mHoustonHomeDir}"
      }

      // Copy Eclipse jar files and other dependencies so that the Kernel can start the OSGi framework
      Closure copyDependency = { File src, File dest ->
        def input = src.newDataInputStream()
        def output = dest.newDataOutputStream()
        output << input
        input.close()
        output.close()
      }
      project.task( name: 'deployLocal', dependsOn: 'unzipHoustonTemplate' ) << {
        project.configurations.getByName( "houston" ).each { dependency ->
          if ( dependency.name.contains( "gradleplugin" ) )
          {
            return
          }
          mBundleStartLevelMap.each { String bundleName, level ->
            if ( bundleName.equals( dependency.name ) )
            {
              copyDependency( dependency, new File( "${mHoustonHomeDir}/bundles/${level}/${dependency.name}" ) )
            }
          }
        }
        // Houston artifacts are deployed to HOUSTON_HOME, so set the correct Kernel version in start up scripts
        setKernelModuleNameAndVersionInStartScripts( project )
      }
      sLogger.info( "*** Added task 'deleteHoustonDir, copyLibs, deployLocal' tasks to the project ..." )
    }
    catch ( Throwable e )
    {
      throw e
    }
  }


  /**
   * Add the task 'bundlor' to OSGIfy the artifact (jar/war) produced by this project.
   * <p />
   * Note: Expects the Bundlor template file in the src/main/resources/META-INF/template.mf to be present
   * <p />
   * @param project - Represents the current Project
   */
  def addBundorTask( Project project )
  {
    def bundlorTask = project.task( name: 'bundlor', dependsOn: 'compileJava' ) {
      description = 'Generates an OSGi-compatibile MANIFEST.MF file.'

      def template = new File( project.projectDir, 'src/main/resources/META-INF/template.mf' )
      def bundlorDir = new File( "${project.buildDir}/bundlor" )
      def manifest = project.file( "${bundlorDir}/META-INF/MANIFEST.MF" )

      // inform gradle what directory this task writes so that
      // it can be removed when issuing `gradle cleanBundlor`
      outputs.dir bundlorDir

      // incremental build configuration
      //   if the $manifest output file already exists, the bundlor
      //   task will be skipped *unless* any of the following are true
      //   * template.mf has been changed
      //   * main classpath dependencies have been changed
      //   * main java sources for this project have been modified
      outputs.files manifest
      inputs.files template, project.sourceSets.main.runtimeClasspath

      // tell the jar task to use bundlor manifest instead of the default
      project.jar.manifest.from manifest
      if ( project.hasProperty( 'war' ) )
      {
        project?.war?.manifest?.from manifest
      }
      // the bundlor manifest should be evaluated as part of the jar task's incremental build
      project.jar.inputs.files manifest
      if ( project.hasProperty( 'war' ) )
      {
        project?.war?.inputs?.files manifest
      }
      // configuration that will be used when creating the ant taskdef classpath
      project.configurations { bundlorconf }
      project.dependencies {
        bundlorconf 'com.springsource.bundlor:com.springsource.bundlor.ant:1.0.0.RELEASE',
          'com.springsource.bundlor:com.springsource.bundlor:1.0.0.RELEASE',
          'com.springsource.bundlor:com.springsource.bundlor.blint:1.0.0.RELEASE'
      }

      doFirst {
        ant.taskdef( resource: 'com/springsource/bundlor/ant/antlib.xml',
          classpath: project.configurations.bundlorconf.asPath )

        // the bundlor ant task writes directly to standard out redirect it to INFO level logging, which gradle will
        // deal with gracefully
        logging.captureStandardOutput( LogLevel.INFO )

        // the ant task will throw unless this dir exists
        if ( !bundlorDir.isDirectory() )
          bundlorDir.mkdir()

        // execute the ant task, and write out the $manifest file
        ant.bundlor( inputPath: "$project.buildDir/classes", outputPath: bundlorDir, manifestTemplatePath: template,
          bundleSymbolicName: "${project.group}" + "-" + "${project.name}", bundleVersion: strippedVersion( (String) project.version ) ) {
          property( name: "bundle.name", value: "${project.group}-${project.name}" )
          property( name: 'created.by', value: 'Islander Total Solutions (ITS)' )
          property( name: 'bundle.description', value: "${project.description}" )
          property( name: 'bundle.vendor', value: "ITS" )
          property( name: 'bundle.createdBy', value: 'Islander Total Solutions (ITS)' )
        }
      }
    }

    project.jar.dependsOn bundlorTask
    if ( project.hasProperty( 'war' ) )
    {
      project?.war?.dependsOn bundlorTask
    }
  }

  /**
   * Strip the "-SNAPSHOT" part from a version number so that it can be set as the 'BundleVersion' OSGi header.
   */
  def strippedVersion( String version )
  {
    String ver = version
    return ver.contains( "-SNAPSHOT" ) ? ver.substring( 0, ver.indexOf( "-SNAPSHOT" ) ) : ver
  }

  /**
   * Add the task 'copyLocal' task which copies the artifact (jar/war) produced by this project to a specified bundle
   * startup level directory. This is useful during local development to quickly test the latest changes contained in the bundle.
   * <p />
   * Note: The current projects build.gradle must define the property 'project.ext.houstonBundlesDir = [start up leve]'
   * <p />
   * @param project - Represents the current Project
   */
  def addCopyToHoustonTask( Project project )
  {
    def targetBundlesDir = "${mHoustonHomeDir}/bundles/${project.ext.houstonBundlesDir}"
    // Copy the artifact (jar/war) built by this project to the specified HOUSTON_HOME/bundles/[targetBundleDir] directory
    project.task( name: 'copyLocal', type: Copy, dependsOn: 'bundlor' ) {
      from "${project.buildDir}/libs/"
      into targetBundlesDir
      exclude '*gradleplugin*'
    }
    // Set the correct Kernel version in start up scripts if the Kernel module is being built
    if ( project.name.contains( 'kernel' ) )
    {
      setKernelModuleNameAndVersionInStartScripts( project )
    }
  }

  /**
   * Set the line "HOUSTON_KERNEL_MODULE= ..." to the current version of the Kernel module in the run.sh|bat file.
   * The version number of the Kernel module is read from the 'houston.kernel.version' of the gradle.properties
   * <p />
   * @param project - Represents the current Project
   */
  def setKernelModuleNameAndVersionInStartScripts( Project project )
  {
    String newVersion = "HOUSTON_KERNEL_MODULE=com.its.houston-kernel-${project.property( 'houston.kernel.version' )}.jar"
    def binDir = new File( "${mHoustonHomeDir}/bin" )
    if ( !binDir.exists() )
    {
      return
    }
    binDir.eachFileRecurse( {file ->
      if ( file.name.endsWith( 'sh' ) )
      {
        def fileText = file.text;
        fileText = fileText.replaceAll( /.*HOUSTON_KERNEL_MODULE=.*/, newVersion )
        file.write( fileText );
      }
      if ( file.name.endsWith( 'bat' ) )
      {
        def fileText = file.text;
        fileText = fileText.replaceAll( ".*SET HOUSTON_KERNEL_MODULE.*", "SET " + newVersion )
        file.write( fileText );
      }
    }
    )
  }

  /**
   * Add the 'proto' tasks to current build. This task executes the Protostuff compiler as a Java process passing all the
   * necessary command line and JVM args to the compiler.
   * <p />
   * NOTE: The project that uses this task must set the 'project.ext.protoBasePackage' property to before calling this
   * task.
   * <p />
   * @param project - Represents the current Project
   */
  def addProtostuffCompileTask( Project project )
  {
    project.sourceSets {
      main {
        java.srcDirs = ["${project.buildDir}/generatedsources-service"]
      }
    }

    project.configurations.create( 'protoStuff' )
	

	    // Hardcode the Protostuff compiler version for now
    project.dependencies {
      protoStuff 'com.dyuproject.protostuff:protostuff-compiler:1.0.7'
    }

    // Generate POJO sources that can be used on the services/backend side
    project.task( name: 'proto', type: JavaExec ) {
      //classpath project.configurations.houston.files
      classpath project.configurations.protoStuff.files
      main 'com.dyuproject.protostuff.compiler.CompilerMain'
      jvmArgs = ["-Dsource=${project.rootDir}/src/main/resources/proto", "-Doutput=java_bean",
        "-DoutputDir=${project.buildDir}/generatedsources-service",
        "-Doptions=generate_field_map:true,builder_pattern:true,compile_imports:recursive"]
    }
  }

  /**
   * Add the 'zipLocal' tasks to current build. This task creates a distro (ZIP file) that contains HOUSTON_HOME/*.*.
   * The name of the distro zip file can be set by the -DHOUSTON_DISTRO_NAME arg. If this arg is not specified, the
   * name of the distro is 'houston-distro.zip' by default.
   * <p />
   * @param project - Represents the current Project
   */
  def addZipHoustonTask( Project project )
  {
    project.task( name: 'zipLocal', type: Zip ) {
      def distroName = System.getProperty( "HOUSTON_DISTRO_NAME" )
      archiveName = distroName == null ? "houston-distro.zip" : "${distroName}.zip"
      destinationDir = new File( "${mHoustonHomeDir}/.." )
      //println "*** Creating Houston distro, name: [${archiveName}], location: [${destinationDir}] ..."

      from( mHoustonHomeDir ) {
        include '**/*'
        //filemode = 777
      }
    }
  }

}



