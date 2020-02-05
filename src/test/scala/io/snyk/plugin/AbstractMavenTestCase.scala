package io.snyk.plugin

import java.io.{File, IOException}
import java.lang.reflect.Modifier
import java.nio.charset.StandardCharsets
import java.util
import java.util.Collections
import java.util.concurrent.TimeUnit

import com.intellij.openapi.application.{ApplicationManager, ReadAction, WriteAction}
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.{LocalFileSystem, VirtualFile}
import com.intellij.testFramework.{EdtTestUtil, RunAll, UsefulTestCase}
import com.intellij.testFramework.fixtures.{IdeaProjectTestFixture, IdeaTestFixtureFactory}
import com.intellij.util.ui.UIUtil
import org.intellij.lang.annotations.Language
import org.jetbrains.idea.maven.indices.MavenIndicesManager
import org.jetbrains.idea.maven.model.MavenExplicitProfiles
import org.jetbrains.idea.maven.project.{MavenArtifactDownloader, MavenProjectsManager, MavenProjectsTree,
  MavenWorkspaceSettings, MavenWorkspaceSettingsComponent}
import org.jetbrains.idea.maven.server.MavenServerManager
import java.{util => ju}

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.projectRoots.impl.JavaAwareProjectJdkTableImpl
import com.intellij.openapi.roots.ModuleRootModificationUtil

abstract class AbstractMavenTestCase extends UsefulTestCase() {
  val POM_XML_FILE_NAME: String = "pom.xml"
  val SETTINGS_XML_FILE_NAME: String = "settings.xml"

  protected var myAllPoms: util.List[VirtualFile] = new util.ArrayList[VirtualFile]

  protected var myProjectsTree: MavenProjectsTree = _
  protected var myProjectsManager: MavenProjectsManager = _

  protected var myTestFixture: IdeaProjectTestFixture = _

  protected var currentProject: Project = _

  protected var projectPomVirtualFile: VirtualFile = _

  @throws[Exception]
  protected def setUpInWriteAction(): Unit = {
    myProjectsManager = MavenProjectsManager.getInstance(currentProject)

    removeFromLocalMavenRepository("test")
  }

  @throws[Exception]
  override protected def setUp(): Unit = {
    super.setUp()

    setUpFixtures()

    currentProject = myTestFixture.getProject

    MavenWorkspaceSettingsComponent.getInstance(currentProject).loadState(new MavenWorkspaceSettings)

    val home = getTestMavenHome

    if (home != null) {
      getMavenGeneralSettings.setMavenHome(home)
    }

    EdtTestUtil.runInEdtAndWait(() => {
      restoreSettingsFile()

      ApplicationManager.getApplication.runWriteAction(new Runnable {
        override def run(): Unit = {
          try setUpInWriteAction()
          catch {
            case throwable: Throwable =>
              throwable.printStackTrace()

              try tearDown()
              catch {
                case exception: Exception =>
                  exception.printStackTrace()
              }
              throw new RuntimeException(throwable)
          }
        }
      })
    })
  }

  @throws[Exception]
  protected def tearDownFixtures(): Unit = {
    //try myTestFixture.tearDown()
    //finally myTestFixture = null

    myTestFixture = null
  }

  @throws[Exception]
  override protected def tearDown(): Unit = {
    new RunAll(
      () => WriteAction.runAndWait(() => JavaAwareProjectJdkTableImpl.removeInternalJdkInTests()),
      () => MavenServerManager.getInstance.shutdown(true),
      () => MavenArtifactDownloader.awaitQuiescence(100, TimeUnit.SECONDS),
      () => currentProject = null,
      () => EdtTestUtil.runInEdtAndWait(() => tearDownFixtures()),
      () => MavenIndicesManager.getInstance.clear(),
      () => super.tearDown(),
      () => resetClassFields(getClass)
    ).run()
  }

  private def resetClassFields(aClass: Class[_]): Unit = {
    if (aClass == null) {
      return
    }

    val fields = aClass.getDeclaredFields

    for (field <- fields) {
      val modifiers = field.getModifiers

      if ((modifiers & Modifier.FINAL) == 0 && (modifiers & Modifier.STATIC) == 0 && !field.getType.isPrimitive) {
        field.setAccessible(true)
        try field.set(this, null)
        catch {
          case e: IllegalAccessException =>
            e.printStackTrace()
        }
      }
    }

    if (aClass eq classOf[AbstractMavenTestCase]) {
      return
    }

    resetClassFields(aClass.getSuperclass)
  }

  @throws[Exception]
  protected def setUpFixtures(): Unit = {
    myTestFixture = IdeaTestFixtureFactory.getFixtureFactory
      .createFixtureBuilder(getName, false).getFixture

    myTestFixture.setUp()
  }

  private def getTestMavenHome = System.getProperty("idea.maven.test.home")

  protected def getMavenGeneralSettings = MavenProjectsManager.getInstance(currentProject).getGeneralSettings

  @throws[IOException]
  protected def restoreSettingsFile(): Unit = {
    updateSettingsXml("")
  }

  @throws[IOException]
  protected def updateSettingsXml(content: String) = {
    val settingsXmlFile = new File(currentProject.getBasePath, SETTINGS_XML_FILE_NAME)
    settingsXmlFile.createNewFile

    val virtualFile = LocalFileSystem.getInstance.refreshAndFindFileByIoFile(settingsXmlFile)

    setFileContent(virtualFile, createSettingsXmlContent(content))

    getMavenGeneralSettings.setUserSettingsFile(virtualFile.getPath)

    virtualFile
  }

  private def createSettingsXmlContent(content: String) = {
    val mirror = System.getProperty("idea.maven.test.mirror", // use JB maven proxy server for internal use by default, see details at
      // https://confluence.jetbrains.com/display/JBINT/Maven+proxy+server
      "http://maven.labs.intellij.net/repo1")
    "<settings>" + content + "<mirrors>" + "  <mirror>" + "    <id>jb-central-proxy</id>" + "    <url>" + mirror + "</url>" + "    <mirrorOf>external:*,!flex-repository</mirrorOf>" + "  </mirror>" + "</mirrors>" + "</settings>"
  }

  private def setFileContent(file: VirtualFile, content: String, advanceStamps: Boolean = true): Unit = {
    try WriteAction.runAndWait(() => {
      if (advanceStamps) {
        file.setBinaryContent(content.getBytes(StandardCharsets.UTF_8), -1, file.getTimeStamp + 4000)
      } else {
        file.setBinaryContent(content.getBytes(StandardCharsets.UTF_8), file.getModificationStamp, file.getTimeStamp)
      }
    })
    catch {
      case ioException: IOException => throw new RuntimeException(ioException)
    }
  }

  protected def createProjectPom(xml: String): VirtualFile = {
    projectPomVirtualFile = createPomXmlVirtualFile(currentProject.getBaseDir, xml)

    projectPomVirtualFile
  }

  protected def createPomXmlVirtualFile(directoryVirtualFile: VirtualFile, xml: String): VirtualFile = {
    val pomXmlFile = new File(currentProject.getBasePath, POM_XML_FILE_NAME)
    pomXmlFile.createNewFile

    val pomXmlVirtualFile = LocalFileSystem.getInstance.refreshAndFindFileByIoFile(pomXmlFile)

    setFileContent(pomXmlVirtualFile, createPomXmlStr(xml))

    myAllPoms.add(pomXmlVirtualFile)

    pomXmlVirtualFile
  }

  @Language(value = "XML")
  def createPomXmlStr(@Language(value = "XML", prefix = "<project>", suffix = "</project>") xmlString: String) =
    "<?xml version=\"1.0\"?>" +
      "<project xmlns=\"http://maven.apache.org/POM/4.0.0\" " +
      "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"" +
      "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">" +
      "  <modelVersion>4.0.0</modelVersion>" +
      xmlString +
      "</project>"

  protected def importProject(@Language(value = "XML", prefix = "<project>", suffix = "</project>") xml: String): Unit = {
    createProjectPom(xml)
    importProject()
  }

  protected def importProject(): Unit = {
    importProjectWithProfiles()
  }

  protected def importProjectWithProfiles(profiles: String = ""): Unit = {
    doImportProjects(Collections.singletonList(projectPomVirtualFile), true, profiles)
  }

  protected def removeFromLocalMavenRepository(relativePath: String = ""): Unit = {
    if (SystemInfo.isWindows) {
      MavenServerManager.getInstance.shutdown(true)
    }

    FileUtil.delete(new File(getRepositoryPath, relativePath))
  }

  protected def getRepositoryPath = {
    val path = getRepositoryFile.getPath
    FileUtil.toSystemIndependentName(path)
  }

  protected def getRepositoryFile = getMavenGeneralSettings.getEffectiveLocalRepository

  protected def doImportProjects(files: ju.List[VirtualFile], failOnReadingError: Boolean, profiles: String): Unit = {
    initProjectsManager(true)

    readProjects(files, profiles)

    UIUtil.invokeAndWaitIfNeeded(new Runnable {
      override def run(): Unit = {
        myProjectsManager.waitForResolvingCompletion()
        myProjectsManager.scheduleImportInTests(files)
        myProjectsManager.importProjects
      }
    })

    if (failOnReadingError) {
      myProjectsTree.getProjects.forEach(mavenProject => {
        println("Failed to import Maven project: " + mavenProject.getProblems, mavenProject.hasReadingProblems)
      })
    }
  }

  protected def initProjectsManager(enableEventHandling: Boolean): Unit = {
    myProjectsManager.initForTests()
    myProjectsTree = myProjectsManager.getProjectsTreeForTests

    //    if (enableEventHandling) {
    //      myProjectsManager.enableAutoImportInTests()
    //    }
  }

  protected def readProjects(files: ju.List[VirtualFile], profiles: String): Unit = {
    myProjectsManager.resetManagedFilesAndProfilesInTests(files, new MavenExplicitProfiles(ju.Arrays.asList(profiles)))

    waitForReadingCompletion()
  }

  protected def waitForReadingCompletion(): Unit = {
    UIUtil.invokeAndWaitIfNeeded(new Runnable {
      override def run(): Unit = {
        try myProjectsManager.waitForReadingCompletion()
        catch {
          case e: Exception =>
            throw new RuntimeException(e)
        }
      }
    })
  }

  protected def setupJdkForModules(moduleNames: String*): Unit = {
    for (each <- moduleNames) {
      setupJdkForModule(each)
    }
  }

  protected def setupJdkForAllModules(): Unit = {
    ModuleManager.getInstance(currentProject).getModules.foreach(module => {
      val sdk = JavaAwareProjectJdkTableImpl.getInstanceEx.getInternalJdk

      ModuleRootModificationUtil.setModuleSdk(module, sdk)
    })
  }

  protected def setupJdkForModule(moduleName: String) = {
    val sdk = JavaAwareProjectJdkTableImpl.getInstanceEx.getInternalJdk

    ModuleRootModificationUtil.setModuleSdk(getModule(moduleName), sdk)

    sdk
  }

  protected def getModule(name: String) =
    ReadAction.compute(() => ModuleManager.getInstance(currentProject).findModuleByName(name))

  protected def waitBackgroundTasks(): Unit = waitBackgroundTasks(6)

  protected def wait10SecondsForBackgroundTasks(): Unit = waitBackgroundTasks(10)

  protected def waitBackgroundTasks(timeoutSeconds: Long): Unit = Thread.sleep(timeoutSeconds * 1000)
}
