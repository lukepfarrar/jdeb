/*
 * Copyright 2012 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.vafer.jdeb.maven;

import com.google.common.io.Files;
import java.io.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;


import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.shared.model.fileset.FileSet;
import org.apache.maven.shared.model.fileset.util.FileSetManager;
import org.apache.tools.tar.TarEntry;
import org.vafer.jdeb.Console;
import org.vafer.jdeb.DataConsumer;
import org.vafer.jdeb.DataProducer;
import org.vafer.jdeb.PackagingException;
import org.vafer.jdeb.descriptors.AbstractDescriptor;
import org.vafer.jdeb.utils.MapVariableResolver;
import org.vafer.jdeb.utils.Utils;
import org.vafer.jdeb.utils.VariableResolver;

/**
 * Creates deb archive
 *
 * @goal jdeb
 */
public class DebMojo extends AbstractPluginMojo {

    /**
     * @component
     */
    private MavenProjectHelper projectHelper;

    /**
     * Defines the pattern of the name of final artifacts. Possible
     * substitutions are [[baseDir]] [[buildDir]] [[artifactId]] [[version]]
     * [[extension]] and [[groupId]].
     *
     * @parameter default-value="[[buildDir]]/[[artifactId]]_[[version]].[[extension]]"
     */
    private String deb;

    /**
     * Explicitly defines the path to the control directory. At least the
     * control file is mandatory.
     *
     * @parameter default-value="[[baseDir]]/src/deb/control"
     */
    private String controlDir;

    /**
     * Explicitly define the file to read the changes from.
     *
     * @parameter default-value="[[baseDir]]/CHANGES.txt"
     */
    private String changesIn;

    /**
     * Explicitly define the file where to write the changes to.
     *
     * @parameter default-value="[[buildDir]]/[[artifactId]]_[[version]].changes"
     */
    private String changesOut;

    /**
     * Explicitly define the file where to write the changes of the changes
     * input to.
     *
     * @parameter default-value="[[baseDir]]/CHANGES.txt"
     */
    private String changesSave;

    /**
     * The compression method used for the data file (none, gzip or bzip2)
     *
     * @parameter default-value="gzip"
     */
    private String compression;


    /**
     * Boolean option whether to attach the artifact to the project
     *
     *  @parameter default-value="true"
     */
    private String attach;

    /**
     * The location where all package files will be installed. By default, all
     * packages are installed in /opt (see the FHS here:
     * http://www.pathname.com/
     * fhs/pub/fhs-2.3.html#OPTADDONAPPLICATIONSOFTWAREPACKAGES)
     *
     * @parameter default-value="/opt/[[artifactId]]"
     */
    private String installDir;


    /**
     * The type of attached artifact
     *
     * @parameter default-value="deb"
     */
    private String type;

    /**
     * The project base directory
     *
     * @parameter default-value="${basedir}"
     * @required
     * @readonly
     */
    private File baseDir;

    /**
     * Run the plugin on all sub-modules.
     * If set to false, the plugin will be run in the same folder where the
     * mvn command was invoked
     * @parameter expression="${submodules}" default-value="true"
     */
    private boolean submodules;

    /**
     * The Maven Session Object
     *
     * @parameter expression="${session}"
     * @required
     * @readonly
     */
    private MavenSession session;

    /**
     * The classifier of attached artifact
     *
     * @parameter
     */
    private String classifier;

    /**
     * "data" entries used to determine which files should be added to this deb.
     * The "data" entries may specify a tarball (tar.gz, tar.bz2, tgz), a
     * directory, or a normal file. An entry would look something like this in
     * your pom.xml:
     *
     * <pre>
     *   <build>
     *     <plugins>
     *       <plugin>
     *       <artifactId>jdeb</artifactId>
     *       <groupId>org.vafer</groupId>
     *       ...
     *       <configuration>
     *         ...
     *         <dataSet>
     *           <data>
     *             <src>${project.basedir}/target/my_archive.tar.gz</src>
     *             <include>...</include>
     *             <exclude>...</exclude>
     *             <mapper>
     *               <type>perm</type>
     *               <strip>1</strip>
     *               <prefix>/somewhere/else</prefix>
     *               <user>santbj</user>
     *               <group>santbj</group>
     *               <mode>600</mode>
     *             </mapper>
     *           </data>
     *           <data>
     *             <src>${project.build.directory}/data</src>
     *             <include></include>
     *             <exclude>**&#47;.svn</exclude>
     *             <mapper>
     *               <type>ls</type>
     *               <src>mapping.txt</src>
     *             </mapper>
     *           </data>
     *         <data>
     *           <src>${project.basedir}/README.txt</src>
     *         </data>
     *         </dataSet>
     *       </configuration>
     *     </plugins>
     *   </build>
     * </pre>
     *
     * @parameter expression="${dataSet}"
     */
    private Data[] dataSet;

    /**
     * The debian conffiles data.
     * 
     * @parameter
     */
    private FileSet[] confFiles;
    
    /**
     * When SNAPSHOT version replace <code>SNAPSHOT</code> with current date
     * and time to make sure each build is unique.
     *
     * @parameter expression="${timestamped}" default-value="false"
     */
    private boolean timestamped;

    /* end of parameters */

    private String openReplaceToken = "[[";
    private String closeReplaceToken = "]]";
    private Collection dataProducers = new ArrayList();

    public void setOpenReplaceToken(String openReplaceToken) {
        this.openReplaceToken = openReplaceToken;
        AbstractDescriptor.setOpenToken(openReplaceToken);
    }

    public void setCloseReplaceToken(String closeReplaceToken) {
        this.closeReplaceToken = closeReplaceToken;
        AbstractDescriptor.setCloseToken(closeReplaceToken);
    }

    protected void setData(Data[] pData) {
        dataSet = pData;
        dataProducers.clear();
        if (pData != null) {
            for (int i = 0; i < pData.length; i++) {
                dataProducers.add(pData[i]);
            }
        }
    }

    protected VariableResolver initializeVariableResolver(Map variables) {
        variables.putAll(getProject().getProperties());

        variables.put("name", getProject().getName());
        variables.put("artifactId", getProject().getArtifactId());
        variables.put("groupId", getProject().getGroupId());
        variables.put("version", getProjectVersion());
        variables.put("description", getProject().getDescription());
        variables.put("extension", "deb");
        variables.put("baseDir", getProject().getBasedir().getAbsolutePath());
        variables.put("buildDir", buildDirectory.getAbsolutePath());
        variables.put("project.version", getProject().getVersion());
        variables.put("url", getProject().getUrl());
        
        return new MapVariableResolver(variables);
    }

    // Generates a "conffiles" file if files are specified in the pom
    private File generateConfFiles() throws MojoExecutionException {
      
        File generatedConfFiles = null;

        List<File> allConfigFiles = new ArrayList<File>();
        
        FileSetManager fileSetManager = new FileSetManager();
        for (FileSet nextFileSet : confFiles) {
            List<String> configFileNames = new ArrayList<String>();

            configFileNames.addAll(Arrays.asList(fileSetManager.getIncludedFiles(nextFileSet)));
            configFileNames.addAll(Arrays.asList(fileSetManager.getIncludedDirectories(nextFileSet)));

            for (String nextFileName : configFileNames) {
                allConfigFiles.add(new File(nextFileSet.getOutputDirectory(), nextFileName));
            }
        }

        if (!allConfigFiles.isEmpty()) {
            PrintWriter printWriter = null;
            generatedConfFiles = new File(Files.createTempDir(), "conffiles");
            try {                
                printWriter = new PrintWriter(new FileOutputStream(generatedConfFiles));
                for (File nextFileName : allConfigFiles) {
                    printWriter.println(nextFileName.getAbsolutePath());
                }
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to write to temporary conffiles file - "+generatedConfFiles.getAbsolutePath(), e);
            } finally {
                printWriter.close();
            }
        }

        return generatedConfFiles;
    }

    /**
     * Doc some cleanup and conversion on the Maven project version.
     * <ul>
     * <li>-: any - is replaced by +</li>
     * <li>SNAPSHOT: replace "SNAPSHOT" par of the version with the current time and date</li>
     * </ul>
     *
     * @return the Maven project version
     */
    private String getProjectVersion() {
        String version = getProject().getVersion().replace('-', '+');

        if (this.timestamped && version.endsWith("+SNAPSHOT")) {
            version = version.substring(0, version.length() - "+SNAPSHOT".length());
            version += "~";
            version += new SimpleDateFormat("yyyyMMdd.HHmmss.SSS").format(new Date());
        }

        return version;
    }

    /**
     * @return whether or not Maven is currently operating in the execution root
     */
    private boolean isSubmodule() {
        // FIXME there must be a better way
        return !session.getExecutionRootDirectory().equalsIgnoreCase(baseDir.toString());
    }

    /**
     * Main entry point
     *
     * @throws MojoExecutionException on error
     */
    public void execute() throws MojoExecutionException {

        final MavenProject project = getProject();

        if(isSubmodule() && !submodules) {
            getLog().info("skipping sub module: jdeb executing at top-level only");
            return;
        }

        setData(dataSet);

        final File generatedConfFiles = generateConfFiles();
        
        try {

            final VariableResolver resolver = initializeVariableResolver(new HashMap());

            final File debFile = new File(Utils.replaceVariables(resolver, deb, openReplaceToken, closeReplaceToken));
            final File controlDirFile = new File(Utils.replaceVariables(resolver, controlDir, openReplaceToken, closeReplaceToken));
            final File installDirFile = new File(Utils.replaceVariables(resolver, installDir, openReplaceToken, closeReplaceToken));
            final File changesInFile = new File(Utils.replaceVariables(resolver, changesIn, openReplaceToken, closeReplaceToken));
            final File changesOutFile = new File(Utils.replaceVariables(resolver, changesOut, openReplaceToken, closeReplaceToken));
            final File changesSaveFile = new File(Utils.replaceVariables(resolver, changesSave, openReplaceToken, closeReplaceToken));

            // if there are no producers defined we try to use the artifacts
            if (dataProducers.isEmpty()) {

                final Set<Artifact> artifacts = project.getArtifacts();
                if (artifacts.size() == 0) {
                    // no artifacts
                    final String packaging = project.getPackaging();
                    if ("pom".equalsIgnoreCase(packaging)) {
                        getLog().warn("Creating empty debian package.");
                    } else {
                        throw new MojoExecutionException(
                            "Nothing to include into the debian package. " +
                            "Did you maybe forget to add a <data> tag or call the plugin directly?");
                    }

                } else {

                    // attach artifacts (jar, war, etc)
                    for(Artifact artifact : artifacts) {
                        final File file = artifact.getFile();
                        if (file != null) {
                            dataProducers.add(new DataProducer() {
                                public void produce(final DataConsumer receiver) {
                                    try {
                                        receiver.onEachFile(
                                                new FileInputStream(file),
                                                new File(installDirFile, file.getName()).getAbsolutePath(),
                                                "",
                                                "root", 0, "root", 0,
                                                TarEntry.DEFAULT_FILE_MODE,
                                                file.length());
                                    } catch (Exception e) {
                                        getLog().error(e);
                                    }
                                }
                            });
                        } else {
                            getLog().error("No file for artifact " + artifact);
                        }
                    }
                }
            }

            Console infoConsole = new Console() {
                public void println(String s) {
                    getLog().info(s);
                }
                public void warn(String message) {
                    getLog().warn(message);
                }
            };

            try {

                DebMaker debMaker = new DebMaker(infoConsole, debFile, controlDirFile, dataProducers, resolver);

                debMaker.setConfFiles(generatedConfFiles);
                
                if (changesInFile.exists() && changesInFile.canRead()) {
                    debMaker.setChangesIn(changesInFile);
                    debMaker.setChangesOut(changesOutFile);
                    debMaker.setChangesSave(changesSaveFile);
                }

                debMaker.setCompression(compression);
                debMaker.makeDeb();

                // Always attach unless explicitly set to false
                if ("true".equalsIgnoreCase(attach)) {
                    getLog().info("Attaching created debian archive " + debFile);
                    projectHelper.attachArtifact(project, type, classifier, debFile);
                }

            } catch (PackagingException e) {
                getLog().error("Failed to create debian package " + debFile, e);
                throw new MojoExecutionException("Failed to create debian package " + debFile, e);
            }

        } catch (ParseException e) {
            throw new MojoExecutionException("Failed parsing pattern", e);
        } finally {
            if (generatedConfFiles != null && generatedConfFiles.exists()) {
                if (!generatedConfFiles.delete()) {
                    generatedConfFiles.deleteOnExit();
                }
            }
        }
    }
}
