/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package nonapi.io.github.classgraph.classpath;

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import nonapi.io.github.classgraph.ScanSpec;
import nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandler;
import nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandlerRegistry;
import nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandlerRegistry.ClassLoaderHandlerRegistryEntry;
import nonapi.io.github.classgraph.utils.FastPathResolver;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.JarUtils;
import nonapi.io.github.classgraph.utils.LogNode;

/** A class to find the unique ordered classpath elements. */
public class ClasspathFinder {
    /** The classpath order. */
    private final ClasspathOrder classpathOrder;

    /** The classloader and module finder. */
    private final ClassLoaderAndModuleFinder classLoaderAndModuleFinder;

    /**
     * The default order in which ClassLoaders are called to load classes, respecting parent-first/parent-last
     * delegation order.
     */
    private ClassLoader[] classLoaderOrderRespectingParentDelegation;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the classpath order.
     *
     * @return The order of raw classpath elements obtained from ClassLoaders.
     */
    public ClasspathOrder getClasspathOrder() {
        return classpathOrder;
    }

    /**
     * Get the classloader and module finder.
     *
     * @return The {@link ClassLoaderAndModuleFinder}.
     */
    public ClassLoaderAndModuleFinder getClassLoaderAndModuleFinder() {
        return classLoaderAndModuleFinder;
    }

    /**
     * Get the ClassLoader order, respecting parent-first/parent-last delegation order.
     *
     * @return the class loader order.
     */
    public ClassLoader[] getClassLoaderOrderRespectingParentDelegation() {
        return classLoaderOrderRespectingParentDelegation;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Find a {@link ClassLoaderHandler} that can handle a given {@link ClassLoader}.
     *
     * @param classLoader
     *            the classloader
     * @param visitedEmbedded
     *            the visited embedded classloaders
     * @param allClassLoaderHandlerRegistryEntries
     *            the ClassLoaderHandler registry entries
     * @param log
     *            the log
     * @return the {@link ClassLoaderHandler}
     */
    private static ClassLoaderHandler findClassLoaderHandler(final ClassLoader classLoader,
            final Set<ClassLoader> visitedEmbedded,
            final List<ClassLoaderHandlerRegistryEntry> allClassLoaderHandlerRegistryEntries, final LogNode log) {
        // Iterate through each superclass of the classloader 
        final Class<? extends ClassLoader> classLoaderClass = classLoader.getClass();
        for (Class<?> superclass = classLoaderClass; superclass != null; superclass = superclass.getSuperclass()) {
            final String superclassName = superclass.getName();
            // Compare against the class names handled by each ClassLoaderHandler
            for (final ClassLoaderHandlerRegistryEntry registryEntry : allClassLoaderHandlerRegistryEntries) {
                for (final String handledClassLoaderName : registryEntry.handledClassLoaderNames) {
                    if (handledClassLoaderName.equals(superclassName)) {
                        // This ClassLoaderHandler can handle this class -- instantiate it
                        final ClassLoaderHandler classLoaderHandler = registryEntry.instantiate(log);
                        if (classLoaderHandler != null) {
                            // If there is an embedded classloader, recursively find the ClassLoaderHandler
                            // for the embedded classloader
                            final ClassLoader embeddedClassLoader = classLoaderHandler
                                    .getEmbeddedClassLoader(classLoader);
                            if (embeddedClassLoader != null && embeddedClassLoader != classLoader
                                    && visitedEmbedded.add(embeddedClassLoader)) {
                                if (log != null) {
                                    log.log("Delegating from " + classLoader.getClass().getName()
                                            + " to embedded ClassLoader "
                                            + embeddedClassLoader.getClass().getName());
                                }
                                return findClassLoaderHandler(embeddedClassLoader, visitedEmbedded,
                                        allClassLoaderHandlerRegistryEntries, log);
                            }
                            // Otherwise, return the found ClassLoaderHandler
                            if (log != null) {
                                log.log("ClassLoader " + classLoader.getClass().getName() + " will be handled by "
                                        + classLoaderHandler.getClass().getName());
                            }
                            return classLoaderHandler;
                        }
                    }
                }
            }
        }
        final ClassLoaderHandler classLoaderHandler = ClassLoaderHandlerRegistry.FALLBACK_CLASS_LOADER_HANDLER
                .instantiate(log);
        if (classLoaderHandler == null) {
            // Should not happen
            throw new RuntimeException("Could not instantiate fallback ClassLoaderHandler");
        }
        if (log != null) {
            log.log("ClassLoader " + classLoader.getClass().getName() + " will be handled by "
                    + classLoaderHandler.getClass().getName());
        }
        return classLoaderHandler;
    }

    /**
     * Perform a topological sort on classloader delegation ordering constraints.
     *
     * @param curr
     *            the current classloader
     * @param visited
     *            the visited classloaders
     * @param orderingConstraints
     *            the ordering constraints
     * @param orderOut
     *            the order out
     */
    private static void topoSort(final ClassLoader curr, final Set<ClassLoader> visited,
            final Map<ClassLoader, List<ClassLoader>> orderingConstraints, final Deque<ClassLoader> orderOut) {
        if (visited.add(curr)) {
            final List<ClassLoader> downstreamNodes = orderingConstraints.get(curr);
            if (downstreamNodes != null) {
                for (final ClassLoader downstreamNode : downstreamNodes) {
                    topoSort(downstreamNode, visited, orderingConstraints, orderOut);
                }
            }
            orderOut.push(curr);
        }
    }

    /**
     * Add an ordering constraint between two classloaders.
     *
     * @param processFirst
     *            the first classloader to try loading a class from
     * @param processSecond
     *            the second classloader to try loading a class from
     * @param orderingConstraints
     *            the ordering constraints
     * @param allDownstreamClassLoaders
     *            all downstream class loaders
     */
    private static void addOrderingConstraint(final ClassLoader processFirst, final ClassLoader processSecond,
            final Map<ClassLoader, List<ClassLoader>> orderingConstraints,
            final Set<ClassLoader> allDownstreamClassLoaders) {
        List<ClassLoader> immediateDownstreamClassLoaders = orderingConstraints.get(processFirst);
        if (immediateDownstreamClassLoaders == null) {
            orderingConstraints.put(processFirst, immediateDownstreamClassLoaders = new ArrayList<>());
        }
        immediateDownstreamClassLoaders.add(processSecond);
        allDownstreamClassLoaders.add(processSecond);
    }

    /**
     * Recursively find the {@link ClassLoaderHandler} that can handle each ClassLoader and its parent(s),
     * respecting parent delegation order (PARENT_FIRST or PARENT_LAST).
     *
     * @param scanSpec
     *            the scan spec
     * @param contextClassLoaders
     *            the context classloader order
     * @param foundClassLoaders
     *            the found classloaders
     * @param allClassLoaderHandlerRegistryEntries
     *            the ClassLoaderHandler registry entries
     * @param classLoaderAndHandlerOrderOut
     *            the classloader and ClassLoaderHandler order
     * @param ignoredClassLoaderAndHandlerOrderOut
     *            the ignored classloader and handler order out
     * @param log
     *            the log
     */
    private void findClassLoaderHandlerForClassLoaderAndParents(final ScanSpec scanSpec,
            final ClassLoader[] contextClassLoaders, final Set<ClassLoader> foundClassLoaders,
            final List<ClassLoaderHandlerRegistryEntry> allClassLoaderHandlerRegistryEntries,
            final List<Entry<ClassLoader, ClassLoaderHandler>> classLoaderAndHandlerOrderOut,
            final List<Entry<ClassLoader, ClassLoaderHandler>> ignoredClassLoaderAndHandlerOrderOut,
            final LogNode log) {
        // Iterate through all classloaders and their ancestors.
        // allClassLoaders is a LinkedHashSet so that the order in which nodes are added, when iterating
        // depth first starting at contextClassLoaders, is preserved.
        final Set<ClassLoader> allClassLoaders = new LinkedHashSet<>();
        final Map<ClassLoader, ClassLoaderHandler> classLoaderToClassLoaderHandler = new HashMap<>();
        final Map<ClassLoader, List<ClassLoader>> orderingConstraints = new HashMap<>();
        final Set<ClassLoader> visitedEmbedded = new HashSet<>();
        final Set<ClassLoader> allParentClassLoaders = new HashSet<>();
        final Set<ClassLoader> allDownstreamClassLoaders = new HashSet<>();
        for (final ClassLoader classLoader : contextClassLoaders) {
            // Iterate through classloader parent hierarchy, trying to find a ClassLoaderHandler that can
            // handle the classloader
            for (ClassLoader ancestorClassLoader = classLoader; ancestorClassLoader != null; //
                    ancestorClassLoader = ancestorClassLoader.getParent()) {
                // Only process each ancestor classloader once
                if (allClassLoaders.add(ancestorClassLoader)) {
                    // Find ClassLoaderHandler for ancestor classloader (will be fallback handler if none found)
                    final ClassLoaderHandler classLoaderHandler = findClassLoaderHandler(ancestorClassLoader,
                            visitedEmbedded, allClassLoaderHandlerRegistryEntries, log);
                    classLoaderToClassLoaderHandler.put(ancestorClassLoader, classLoaderHandler);

                    // Find delegation order of ancestor classloader relative to its parent
                    final ClassLoader ancestorParent = ancestorClassLoader.getParent();
                    if (ancestorParent != null) {
                        // Record parents
                        allParentClassLoaders.add(ancestorParent);

                        // Build a DAG using the delegation order
                        switch (classLoaderHandler.getDelegationOrder(ancestorClassLoader)) {
                        case PARENT_FIRST:
                            addOrderingConstraint(ancestorParent, ancestorClassLoader, orderingConstraints,
                                    allDownstreamClassLoaders);
                            break;
                        case PARENT_LAST:
                            addOrderingConstraint(ancestorClassLoader, ancestorParent, orderingConstraints,
                                    allDownstreamClassLoaders);
                            break;
                        default:
                            // Should not happen
                            throw new RuntimeException("Invalid delegation order");
                        }
                    }
                } else {
                    // Already processed this ancestor and all of its ancestors
                    break;
                }
            }
        }

        // Find all DAG root nodes, which are all nodes that are not downstream of another node
        final Set<ClassLoader> rootClassLoaders = new LinkedHashSet<>(allClassLoaders);
        rootClassLoaders.removeAll(allDownstreamClassLoaders);

        // Perform a topological sort on the DAG of parent delegation order constraints, starting at the roots
        final Set<ClassLoader> visited = new HashSet<>();
        final Deque<ClassLoader> topoSortOrder = new LinkedList<>();
        for (final ClassLoader rootClassLoader : rootClassLoaders) {
            topoSort(rootClassLoader, visited, orderingConstraints, topoSortOrder);
        }

        // If ignoring parent classloaders, split resulting list into parents and non-parents
        for (final ClassLoader classLoader : topoSortOrder) {
            final ClassLoaderHandler classLoaderHandler = classLoaderToClassLoaderHandler.get(classLoader);
            final Entry<ClassLoader, ClassLoaderHandler> ent = new SimpleEntry<>(classLoader, classLoaderHandler);
            if (classLoaderHandler == null) {
                // Should not happen
                throw new RuntimeException("ClassLoaderHandler not found");
            }
            if (scanSpec.ignoreParentClassLoaders && allParentClassLoaders.contains(classLoader)) {
                ignoredClassLoaderAndHandlerOrderOut.add(ent);
            } else {
                classLoaderAndHandlerOrderOut.add(ent);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * A class to find the unique ordered classpath elements.
     * 
     * @param scanSpec
     *            The {@link ScanSpec}.
     * @param log
     *            The log.
     */
    public ClasspathFinder(final ScanSpec scanSpec, final LogNode log) {
        final LogNode classpathFinderLog = log == null ? null : log.log("Finding classpath and modules");

        // If system jars are not blacklisted, add JRE rt.jar to the beginning of the classpath
        final String jreRtJar = SystemJarFinder.getJreRtJarPath();
        final boolean scanAllLibOrExtJars = !scanSpec.libOrExtJarWhiteBlackList.whitelistAndBlacklistAreEmpty();
        final LogNode systemJarsLog = classpathFinderLog == null ? null : classpathFinderLog.log("System jars:");

        classLoaderAndModuleFinder = new ClassLoaderAndModuleFinder(scanSpec, classpathFinderLog);

        classpathOrder = new ClasspathOrder(scanSpec);
        final ClasspathOrder ignoredClasspathOrder = new ClasspathOrder(scanSpec);

        final ClassLoader[] contextClassLoaders = classLoaderAndModuleFinder.getContextClassLoaders();
        final ClassLoader defaultClassLoader = contextClassLoaders != null && contextClassLoaders.length > 0
                ? contextClassLoaders[0]
                : null;
        if (scanSpec.overrideClasspath != null) {
            // Manual classpath override
            if (scanSpec.overrideClassLoaders != null && classpathFinderLog != null) {
                classpathFinderLog.log("It is not possible to override both the classpath and the ClassLoaders -- "
                        + "ignoring the ClassLoader override");
            }
            final LogNode overrideLog = classpathFinderLog == null ? null
                    : classpathFinderLog.log("Overriding classpath with: " + scanSpec.overrideClasspath);
            classpathOrder.addClasspathEntries(scanSpec.overrideClasspath, defaultClassLoader, overrideLog);
            if (overrideLog != null) {
                overrideLog.log("WARNING: when the classpath is overridden, there is no guarantee that the classes "
                        + "found by classpath scanning will be the same as the classes loaded by the "
                        + "context classloader");
            }
            classLoaderOrderRespectingParentDelegation = contextClassLoaders;

        } else {
            // Add rt.jar and/or lib/ext jars to beginning of classpath, if enabled
            if (jreRtJar != null) {
                if (scanSpec.enableSystemJarsAndModules) {
                    classpathOrder.addSystemClasspathEntry(jreRtJar, defaultClassLoader);
                    if (systemJarsLog != null) {
                        systemJarsLog.log("Found rt.jar: " + jreRtJar);
                    }
                } else if (systemJarsLog != null) {
                    systemJarsLog.log((scanSpec.enableSystemJarsAndModules ? "" : "Scanning disabled for rt.jar: ")
                            + jreRtJar);
                }
            }
            for (final String libOrExtJarPath : SystemJarFinder.getJreLibOrExtJars()) {
                if (scanAllLibOrExtJars || scanSpec.libOrExtJarWhiteBlackList
                        .isSpecificallyWhitelistedAndNotBlacklisted(libOrExtJarPath)) {
                    classpathOrder.addSystemClasspathEntry(libOrExtJarPath, defaultClassLoader);
                    if (systemJarsLog != null) {
                        systemJarsLog.log("Found lib or ext jar: " + libOrExtJarPath);
                    }
                } else if (systemJarsLog != null) {
                    systemJarsLog.log("Scanning disabled for lib or ext jar: " + libOrExtJarPath);
                }
            }

            // List ClassLoaderHandlers
            if (classpathFinderLog != null) {
                final LogNode classLoaderHandlerLog = classpathFinderLog.log("ClassLoaderHandlers:");
                for (final ClassLoaderHandlerRegistryEntry classLoaderHandlerEntry : //
                ClassLoaderHandlerRegistry.CLASS_LOADER_HANDLERS) {
                    classLoaderHandlerLog.log(classLoaderHandlerEntry.classLoaderHandlerClass.getName());
                }
            }

            // Find all unique parent ClassLoaders, and put all ClassLoaders into a single order, according to the
            // delegation order (PARENT_FIRST or PARENT_LAST)
            final List<Entry<ClassLoader, ClassLoaderHandler>> classLoaderAndHandlerOrder = new ArrayList<>();
            final List<Entry<ClassLoader, ClassLoaderHandler>> ignoredClassLoaderAndHandlerOrder = //
                    new ArrayList<>();
            if (contextClassLoaders != null) {
                findClassLoaderHandlerForClassLoaderAndParents(scanSpec, contextClassLoaders,
                        /* foundClassLoaders = */ new LinkedHashSet<ClassLoader>(),
                        ClassLoaderHandlerRegistry.CLASS_LOADER_HANDLERS, classLoaderAndHandlerOrder,
                        ignoredClassLoaderAndHandlerOrder, classpathFinderLog);
            }

            // Call each ClassLoaderHandler on its corresponding ClassLoader to get the classpath URLs or paths
            final LogNode classLoaderClasspathLoopLog = classpathFinderLog == null ? null
                    : classpathFinderLog.log("Finding classpath elements in ClassLoaders");
            final LinkedHashSet<ClassLoader> classLoaderOrderRespectingParentDelegationSet = new LinkedHashSet<>();
            for (final Entry<ClassLoader, ClassLoaderHandler> classLoaderAndHandler : classLoaderAndHandlerOrder) {
                final ClassLoader classLoader = classLoaderAndHandler.getKey();
                classLoaderOrderRespectingParentDelegationSet.add(classLoader);
                final ClassLoaderHandler classLoaderHandler = classLoaderAndHandler.getValue();
                final LogNode classLoaderClasspathLog = classLoaderClasspathLoopLog == null ? null
                        : classLoaderClasspathLoopLog.log(
                                "Finding classpath elements in ClassLoader " + classLoader.getClass().getName());
                try {
                    classLoaderHandler.handle(scanSpec, classLoader, classpathOrder, classLoaderClasspathLog);
                } catch (final RuntimeException | LinkageError e) {
                    if (classLoaderClasspathLog != null) {
                        classLoaderClasspathLog.log("Exception in ClassLoaderHandler", e);
                    }
                }
            }
            // Need to record the proper order in which classloaders should be called, in particular to
            // respect parent-last delegation order, since this is not the default (issue #267).
            classLoaderOrderRespectingParentDelegation = classLoaderOrderRespectingParentDelegationSet
                    .toArray(new ClassLoader[0]);

            // Repeat the process for ignored parent ClassLoaders
            for (final Entry<ClassLoader, ClassLoaderHandler> classLoaderAndHandler : //
            ignoredClassLoaderAndHandlerOrder) {
                final ClassLoader classLoader = classLoaderAndHandler.getKey();
                final ClassLoaderHandler classLoaderHandler = classLoaderAndHandler.getValue();
                final LogNode classLoaderClasspathLog = classpathFinderLog == null ? null
                        : classpathFinderLog
                                .log("Will not scan the following classpath elements from ignored ClassLoader "
                                        + classLoader.getClass().getName());
                try {
                    classLoaderHandler.handle(scanSpec, classLoader, ignoredClasspathOrder,
                            classLoaderClasspathLog);
                } catch (final RuntimeException | LinkageError e) {
                    if (classLoaderClasspathLog != null) {
                        classLoaderClasspathLog.log("Exception in ClassLoaderHandler", e);
                    }
                }
            }

            // Get classpath elements from java.class.path, but don't add them if the element is in an ignored
            // parent classloader and not in a child classloader (and don't use java.class.path at all if
            // overrideClassLoaders is true or overrideClasspath is set)
            if (scanSpec.overrideClassLoaders == null && scanSpec.overrideClasspath == null) {
                final String[] pathElements = JarUtils.smartPathSplit(System.getProperty("java.class.path"));
                if (pathElements.length > 0) {
                    final LogNode sysPropLog = classpathFinderLog == null ? null
                            : classpathFinderLog.log("Getting classpath entries from java.class.path");
                    for (final String pathElement : pathElements) {
                        final String pathElementResolved = FastPathResolver.resolve(FileUtils.CURR_DIR_PATH,
                                pathElement);
                        if (!ignoredClasspathOrder.getClasspathEntryUniqueResolvedPaths()
                                .contains(pathElementResolved)) {
                            // pathElement is not also listed in an ignored parent classloader
                            classpathOrder.addClasspathEntry(pathElement, defaultClassLoader, sysPropLog);
                        } else {
                            // pathElement is also listed in an ignored parent classloader, ignore it (Issue #169)
                            if (sysPropLog != null) {
                                sysPropLog.log("Found classpath element in java.class.path that will be ignored, "
                                        + "since it is also found in an ignored parent classloader: "
                                        + pathElement);
                            }
                        }
                    }
                }
            }
        }
    }
}
