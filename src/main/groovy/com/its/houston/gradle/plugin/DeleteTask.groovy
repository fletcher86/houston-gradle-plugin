package com.its.houston.gradle.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

class DeleteTask extends DefaultTask {

	@TaskAction
	def delete() {
		delete "${mHoustonHomeDir}"
	}
}
