package com.github.xuwei_k

import sbt._
import sbt.Keys._
import com.gargoylesoftware.htmlunit.{BrowserVersion,WebClient}
import com.gargoylesoftware.htmlunit.html._
import java.io.{BufferedWriter,File}
import java.net.InetAddress
import java.util.Properties
import org.apache.commons.io.output.FileWriterWithEncoding
import scala.xml._
import com.gargoylesoftware.htmlunit.xml.XmlPage
import scala.actors.Futures._
import org.apache.commons.logging.LogFactory

case class ResolvedArtifact(groupId: String, artifactId: String, version: String){
  override def toString = """ "%s" %% "%s" %% "%s" """.format(groupId, artifactId, version)
  def isSame(another: (String, String)) = another._1 == groupId && another._2 == artifactId
}

object SbtLibPlugin extends sbt.Plugin{
  
  // Needed to supress HtmlUnit warnings
  LogFactory.getFactory.setAttribute("org.apache.commons.logging.Log", "org.apache.commons.logging.impl.NoOpLog")  
  
  /**
  * Get list of jar names in /lib folder
  */  
  def getLibraryJarNames = {
    IO.listFiles(file("lib"),GlobFilter("*.jar") ).map(_.asFile.getName)  
  }
  
  /**
  * Processes resource identified with URL in HtmlUnit
  */
  private def processInWebClient[T](url: String)(request: HtmlPage => T) = {
    val webClient = new WebClient   
    val result = request(webClient.getPage(url))
    webClient.closeAllWindows
    result
  }  
    
  /**
  * Resolves Maven artifact by jar-name
  */
  def resolveArtifactsByJarName(jarName: String,log: Logger) = 
    processInWebClient("http://www.jarvana.com/jarvana/search?search_type=project&project=%s" format(jarName)){page =>
      parseLibSearchResults(page,log)
    }    
  
  /**
  * Resolves Maven artifacts corresponding to jars in the /lib folder
    */  
  def resolveLibArtifacts(log: Logger) = 
    getLibraryJarNames.
    map(jarName => 
        future[Seq[Either[String, ResolvedArtifact]]]{
          resolveArtifactsByJarName(jarName.replace("jar", "pom"),log) match {
            case Nil => List(Left(jarName))
            case list => list.map(Right(_))
          }
        }).
    map(_.apply).toList.flatten[Either[String, ResolvedArtifact]].distinct       
  
  /**
  * Resolves Maven artifact by class 
  */
  def resolveArtifactByClass(className: String, pageNum: Int , log: Logger) = 
    processInWebClient("http://www.jarvana.com/jarvana/search?search_type=class&java_class=%s&start=%s"
    .format(className, (pageNum - 1) * 100)){page =>
      val numerOfPages = (page.getElementById("browseindex") match {
          case element:HtmlElement => Some(element)
          case null => None
        }).map(_.getChildren.iterator.next.getTextContent)
        .filter(_.contains("Class Name Search: Results"))
        .map(_.split("of ")(1).split(" for")(0).replaceAll(",", "").toInt / 100 + 1).getOrElse(1)    
            try{log.info("\r\nDisplaying artifacts: page %s of %s\r\n" format(pageNum, numerOfPages))} catch {case _ =>}
      parseClassSearchResults(page,log)
    }  
  
  /**
  * Parse HTML search result table (@jarvana.com)
  */
  private def parseResultTable(page: HtmlPage, rowParser: NodeSeq => ResolvedArtifact, log: Logger) = 
    try {
      (XML.loadString((page.getHtmlElementById("resulttable"):HtmlTable).asXml) \\ "tr")
      .drop(1).map(row => rowParser(row \\ "td" drop(3)))
    } catch{case e=> log.debug(e.getMessage); Nil}              

  /**
  * Parse HTML search result table, returned when searching by class name
  */
  private def parseClassSearchResults(page: HtmlPage,log: Logger) = 
    parseResultTable(page, {row =>
      ResolvedArtifact(Some(row(0).child.last.text.trim).map(str => str.substring(1, str.length - 1)).get, 
        Some(row(1).child.last.text.trim).map(str => str.substring(1, str.length - 1)).get, 
        (row(2) \\ "a").text.trim)    
    },log)  

  /**
  * Parse HTML search result table, returned when searching by jar name
  */
  private def parseLibSearchResults(page: HtmlPage,log: Logger) = 
    parseResultTable(page, {row =>
      ResolvedArtifact(row(0).child.last.text.trim.drop(1),
        row(1).child.last.text.trim.drop(1), 
        (row(2) \\ "a").text.trim)    
    },log)    
    
  private def groupArtifacts(art: Seq[ResolvedArtifact]) = {
    val keys = art.map(artifact => (artifact.groupId, artifact.artifactId)).toList.distinct
    (List[((String, String), List[String])]() /: keys){
      (group, key) =>
      ((key._1, key._2), art.filter(_.isSame(key)).map(_.version).toList) :: group
    }
  }    
    
  private def printMultiversionedArtifacts(artifacts: Seq[ResolvedArtifact], log: Logger) =
    groupArtifacts(artifacts).
    map(entity => log.info(""" "%s" %% "%s" %% "%s" """.
    format(entity._1._1, entity._1._2, entity._2.mkString(", "))))  
    
  def printResults(resolvingResults: Seq[Either[String, ResolvedArtifact]], log: Logger) = {    
    val resolvedArtifacts = resolvingResults.flatMap{case Right(artifact) => Some(artifact); case _ => None}
    if(resolvedArtifacts.size != 0){
      log.info("\r\n==================================================")
      log.info("==============SUCCESSFULLY RESOLVED===============")
      log.info("==================================================\r\n")
      printMultiversionedArtifacts(resolvedArtifacts,log)
      log.info("")
    }
    if(resolvedArtifacts.size != resolvingResults.size){
      log.info("\r\n==================================================")
      log.info("================FAILED TO RESOLVE=================")
      log.info("==================================================\r\n")    
      resolvingResults.foreach(_.left.map(name => log.info(" " + name.replace("pom", "jar"))))
      log.info("")
    }
  }  

  lazy val libs = TaskKey[Unit]("libs") 

  lazy val resolveLibs = TaskKey[Unit]("resolve-libs")

  lazy val resolveLib = InputKey[Unit]("resolve-lib","resolve-lib")

  lazy val resolveClass = TaskKey[Unit]("resolve-class")

  val Config = config("sbt-lib-resolver") extend (Runtime)

  override lazy val settings: Seq[sbt.Project.Setting[_]] = inConfig(Config)(
    Seq(
      libs <<= (streams) map { s =>
        getLibraryJarNames.foreach(s.log.info(_))
      }  
      ,
      resolveLibs <<= (streams) map { s =>
        printResults(resolveLibArtifacts( s.log ),s.log)
      }
      ,
      resolveLib <<= inputTask { (a: TaskKey[Seq[String]]) =>
        (a,streams) map { (args,s) =>
          val log = s.log
          if(args.length == 1){
            printResults(resolveArtifactsByJarName(args(0),log).map(Right(_)),log)
          }else
            print("Usage: resolve-lib <jar name>.jar") 
        } //completeWith getLibraryJarNames.toSeq
      }
    )
  )
/*
  lazy val resolveLib = task { args =>
    if(args.length == 1){
      printResults(resolveArtifactsByJarName(args(0)).map(Right(_)))
      task { None }
    }else
      task { Some("Usage: resolve-lib <jar name>.jar") }
  } completeWith getLibraryJarNames.toSeq

  lazy val resolveClass = task { args =>
    if(args.length > 0){
      log.info("Only 100 artifacts will be displayed per request. If more artifacts are found, go to another page: resolve-class <class name> <page of results>")
      printResults(resolveArtifactByClass(args(0), 
          if(args.length > 1) args(1).toInt else 1      
      ).map(Right(_)))
      task { None }
    }else
      task { Some("Usage: resolve-class <class name> <page of results>") }
  }  
 */ 
}

