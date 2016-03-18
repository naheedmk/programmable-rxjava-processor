/*
 * Copyright 2016 the original author or authors.
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
package org.springframework.cloud.stream.module.transform.javacompiler;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.tools.FileObject;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import javax.tools.StandardLocation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A file manager that serves source code from in memory and ensures output results are kept in memory
 * rather than being flushed out to disk. The JavaFileManager is also used as a lookup mechanism
 * for resolving types and this version satisfies lookup requests by using the information available
 * from the classloaders in use. This behaves better under a real SCDF environment where the java.class.path
 * has been stripped.
 *
 * @author Andy Clement
 */
public class MemoryBasedJavaFileManager implements JavaFileManager {

	private static Logger logger = LoggerFactory.getLogger(MemoryBasedJavaFileManager.class);
	
	private CompilationOutputCollector outputCollector;

	// Any other source files that this file manager knows about
	private List<String> sourceFiles = new ArrayList<>();

	private List<CloseableJavaFileObjectIterable> toClose = new ArrayList<>();

	public MemoryBasedJavaFileManager() {
		outputCollector = new CompilationOutputCollector();
	}

	public void addSourceFile(String source) {
		sourceFiles.add(source);
	}

	@Override
	public int isSupportedOption(String option) {
		logger.debug("isSupportedOption({})",option);
		return -1; // Not yet supporting options
	}

	@Override
	public ClassLoader getClassLoader(Location location) {
		// Do not simply return the context classloader as it may get closed and then
		// be unusable for loading any further classes
		logger.debug("getClassLoader({})",location);
		return null; // Do not currently need to load plugins
	}

	@Override
	public Iterable<JavaFileObject> list(Location location, String packageName, Set<Kind> kinds, boolean recurse)
			throws IOException {
		logger.debug("list({},{},{},{})",location,packageName,kinds,recurse);

		CloseableJavaFileObjectIterable resultIterable = null;
		if (location == StandardLocation.PLATFORM_CLASS_PATH) {
			// Iterate over the sun.boot.class.path
			if (logger.isDebugEnabled()) {
				logger.debug("Creating iterable for boot class path: {}",System.getProperty("sun.boot.class.path"));
			}
			resultIterable = new IterableClasspath("sun.boot.class.path", packageName, recurse);
		} else if (location == StandardLocation.CLASS_PATH) {
			// Ideally this would iterate over java.class.path but in some environments that
			// has been emptied and the only clear view of the types available is through the
			// classloaders
			ClassLoader currentClassLoader = this.getClass().getClassLoader();
			URL[] urls = collectURLs(currentClassLoader);
			if (urls.length!=0) {
				resultIterable = new IterableUrlList(urls, packageName, recurse);
			} else {
				resultIterable = EmptyIterable.instance;
			}
			// This is the alternative code that would walk up a java.class.path
			// 	resultIterable = new IterableClasspath("java.class.path", packageName, recurse);
		} else if (location == StandardLocation.SOURCE_PATH) {
			resultIterable = EmptyIterable.instance;//new IterableSources(sourceFiles);
		} else {
			throw new IllegalStateException("Requests against this location are not supported: "+location.toString());
		}
		toClose.add(resultIterable);
		return resultIterable;
	}

	/**
	 * Walk up a classloader hierarchy discovering the URLs available. Only
	 * works properly with URLClassLoaders.
	 * 
	 * @param cl classloader to start at
	 * @return an array of discovered URLs
	 */
	private URL[] collectURLs(ClassLoader cl) {
		List<URL> urls = new ArrayList<>();
		collect(urls,cl);
		return urls.toArray(new URL[0]);
	}
	
	private void collect(List<URL> collector, ClassLoader loader) {
		logger.debug("Collecting URLs from classloader "+loader);
		if (loader instanceof URLClassLoader) {
			for (URL url: ((URLClassLoader)loader).getURLs()) {
				collector.add(url);
			}
		}
		ClassLoader parent = loader.getParent();
		if (parent !=null) {
			collect(collector, parent);
		}
	}

	@Override
	public String inferBinaryName(Location location, JavaFileObject file) {
		if (location == StandardLocation.SOURCE_PATH) {
			throw new IllegalStateException("inferBinaryName against the source path location is not supported");
		}
		// Example value from getClassName(): javax/validation/bootstrap/GenericBootstrap.class
		String classname = ((ExtendedJavaFileObject) file).getClassName().replace('/', '.');
		return classname.substring(0, classname.lastIndexOf(".class"));
	}

	@Override
	public boolean isSameFile(FileObject a, FileObject b) {
		logger.debug("isSameFile({},{})",a,b);
		throw new IllegalStateException("Not expected to be used in this context");
	}

	@Override
	public boolean handleOption(String current, Iterator<String> remaining) {
		logger.debug("handleOption({},{})",current,remaining);
		throw new IllegalStateException("Not expected to be used in this context");
	}

	@Override
	public boolean hasLocation(Location location) {
		logger.debug("hasLocation({})",location);
		if (location == StandardLocation.ANNOTATION_PROCESSOR_PATH) {
			return false;
		}
		if (location == StandardLocation.SOURCE_PATH) {
			return true;
		}
		return false;
	}

	@Override
	public JavaFileObject getJavaFileForInput(Location location, String className, Kind kind) throws IOException {
		logger.debug("getJavaFileForInput({},{},{})",location,className,kind);
		throw new IllegalStateException("Not expected to be used in this context");
	}

	@Override
	public JavaFileObject getJavaFileForOutput(Location location, String className, Kind kind, FileObject sibling)
			throws IOException {
		logger.debug("getJavaFileForOutput({},{},{},{})",location,className,kind,sibling);
		// Example parameters: CLASS_OUTPUT, Foo, CLASS, StringBasedJavaSourceFileObject[string:///a/b/c/Foo.java]
		return outputCollector.getFileForOutput(location, className, kind, sibling);
	}

	@Override
	public FileObject getFileForInput(Location location, String packageName, String relativeName) throws IOException {
		logger.debug("getFileForInput({},{},{})",location,packageName,relativeName);
		throw new IllegalStateException("Not expected to be used in this context");
	}

	@Override
	public FileObject getFileForOutput(Location location, String packageName, String relativeName, FileObject sibling)
			throws IOException {
		logger.debug("getFileForOutput({},{},{},{})",location,packageName,relativeName,sibling);
		// This can be called when the annotation config processor runs
		// Example parameters: CLASS_OUTPUT, , META-INF/spring-configuration-metadata.json, null
		return outputCollector.getFileForOutput(location, packageName, relativeName, sibling);
	}

	@Override
	public void flush() throws IOException {
	}

	@Override
	public void close() throws IOException {
		for (CloseableJavaFileObjectIterable closeable: toClose) {
			closeable.close();
		}
	}

	public List<CompiledClassDefinition> getCompiledClasses() {
		return outputCollector.getCompiledClasses();
	}

}