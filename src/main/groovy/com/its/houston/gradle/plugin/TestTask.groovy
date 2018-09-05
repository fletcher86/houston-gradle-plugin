package com.its.houston.gradle.plugin

import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.TaskAction

/**
 * <code></code>
 * <p/>
 * <p />
 * @author rajiv@itstcb.com
 * @since May 2012
 */

class TestTask
extends JavaExec
{
  @TaskAction
  comp()
  {
    println "88888888"
  }

}
