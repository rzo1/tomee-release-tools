/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.openejb.tools.release.cmd;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.tomitribe.crest.api.Command;
import org.tomitribe.crest.api.Default;
import org.tomitribe.crest.api.Option;
import org.tomitribe.crest.api.Out;
import org.tomitribe.swizzle.stream.StreamBuilder;
import org.tomitribe.util.Files;
import org.tomitribe.util.Hex;
import org.tomitribe.util.IO;
import org.tomitribe.util.dir.Dir;
import org.tomitribe.util.dir.Filter;
import org.tomitribe.util.dir.Walk;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.String.format;
import static org.apache.openejb.tools.release.util.Exec.exec;

@Command
public class Dist {

    /**
     * Download binaries from a maven repo and commit them to dist.apache.org dev
     *
     * The org/apache/tomee/apache-tomee and org/apache/tomee/tomee-project sections
     * of the maven repository will be scanned for the version specified and all zip
     * and tar.gz files will be downloaded along with any associated asc and sha1 files.
     * After download the sha1 files of each binary will be checked to ensure a complete
     * download.  The sha256 and sha512 file for each binary will be computed and written
     * to disk.
     *
     * If the --dry-run flag is not enabled, the resulting zip, tar.gz, asc, sha256 and
     * sha512 files will be uploaded to a directory in dist.apache.org dev or the specified
     * svn repo.
     *
     * When ready, the dist.apache.org dev directory can be moved to dist.apache.org release
     * via the `dist dev-to-release` command.
     *
     * @param version The TomEE version being published.  Example: 8.0.7
     * @param tmp The directory under which files can be temporarily downloaded
     * @param mavenRepoUri  The root path of a Nexus staging repository or Maven Central
     * @param svnRepo The svn directory for tomee where a subdirectory can be created and binaries uploaded
     * @param dryRun Download the files to local disk, but do not commit them to svn
     */
    @Command("maven-to-dev")
    public void mavenToDev(final String version,
                           @Option("tmp") @Default("/tmp/") final File tmp,
                           @Option("maven-repo") @Default("https://repo1.maven.org/maven2/") final URI mavenRepoUri,
                           @Option("svn-repo") @Default("https://dist.apache.org/repos/dist/dev/tomee/") final URI svnRepo,
                           @Option("dry-run") @Default("false") final boolean dryRun,
                           final @Out PrintStream out) throws IOException {


        final String build = buildId(mavenRepoUri);
        final String tomeeVersionName = "tomee-" + version;
        final String svnBinaryLocation = format("https://dist.apache.org/repos/dist/dev/tomee/staging-%s/%s", build, tomeeVersionName);

        final File dir = new File(tmp, "tomee-" + version + "-work");

        { // Make and checkout the binaries dir in svn
            if (!dir.exists()) {
                Files.mkdirs(dir);
            }

            if (!dryRun) {
                exec("svn", "-m", format("[release-tools] staged binary dir for %s", tomeeVersionName), "mkdir", "--parents", svnBinaryLocation);
                exec("svn", "co", svnBinaryLocation, dir.getAbsolutePath());
            }
        }


        final MavenRepo repo = new MavenRepo(mavenRepoUri, out);

        final List<URI> binaries = new ArrayList<>();
        binaries.addAll(repo.binaries("org/apache/tomee/apache-tomee/", version));
        binaries.addAll(repo.binaries("org/apache/tomee/tomee-project/", version));

        binaries.forEach(repo.downloadTo(dir));
        out.printf("Downloaded %s binaries to %s%n", binaries.size(), dir.getAbsolutePath());

        final Work work = Dir.of(Work.class, dir);

        final List<Binary> invalid = work.binaries()
                .filter(((Predicate<Binary>) Binary::verifySha1).negate())
                .collect(Collectors.toList());

        if (invalid.size() != 0) {
            invalid.forEach(binary -> out.printf("SHA1 check failed %s%n", binary.get().getAbsolutePath()));
            throw new CommandFailedException("Remove the invalid files and try again");
        }

        work.binaries()
                .peek(Binary::createSha256)
                .peek(Binary::createSha512)
                .forEach(binary -> out.println("Hashed " + binary.get().getName()));

        if (!dryRun) {

            Consumer<File> svnAdd = file -> exec("svn", "add", file.getAbsolutePath());

            work.binaries()
                    .peek(binary -> svnAdd.accept(binary.get()))
                    .peek(binary -> svnAdd.accept(binary.asc()))
                    .peek(binary -> svnAdd.accept(binary.sha256()))
                    .peek(binary -> svnAdd.accept(binary.sha512()))
                    .forEach(binary -> out.println("Added " + binary.get().getName()));

            exec("svn", "-m", format("[release-tools] staged binaries for %s", tomeeVersionName), "ci", dir.getAbsolutePath());

            out.printf("Binaries published to %s%n", svnBinaryLocation);
        }
    }

    /**
     * Return the last digits of a Nexus staging repo dir such as orgapachetomee-1136 or
     * return the month and day as a default.
     */
    private String buildId(final URI stagingRepoUri) {
        final String id = stagingRepoUri.getPath().replaceAll(".*-([0-9]+)/?$", "$1");
        if (id.matches("^[0-9]+$")) {
            return id;
        }

        final SimpleDateFormat format = new SimpleDateFormat("MMdd");
        return format.format(new Date());
    }

    @Command("dev-to-release")
    public void release(final String version,
                        @Option("dev-repo") @Default("https://repo1.maven.org/maven2/") final URI mavenRepo,
                        @Option("release-repo") @Default("https://dist.apache.org/repos/dist/release/tomee/") final URI svnRepo,
                        final @Out PrintStream out) throws IOException {

    }

    public static class MavenRepo {
        private final CloseableHttpClient client = HttpClientBuilder.create().build();
        private final URI repo;
        private final PrintStream out;

        public MavenRepo(final URI repo, final PrintStream out) {
            this.repo = repo;
            this.out = out;
        }

        public List<URI> binaries(final String artifactPath, final String version) throws IOException {
            final URI artifactDir = this.repo.resolve(artifactPath);

            final URI versionDir = artifactDir.resolve(version + "/");
            final CloseableHttpResponse response = get(versionDir);

            final List<String> hrefs = new ArrayList<>();
            StreamBuilder.create(response.getEntity().getContent())
                    .watch("<a href=\"", "\"", hrefs::add)
                    .run();

            final Predicate<String> acceptedExtensions = Pattern.compile("\\.(zip|tar\\.gz)(\\.(asc|sha1))?$").asPredicate();
            return hrefs.stream()
                    .filter(acceptedExtensions)
                    .map(versionDir::resolve)
                    .collect(Collectors.toList());
        }

        private CloseableHttpResponse get(final URI uri) throws IOException {
            final CloseableHttpResponse response = client.execute(new HttpGet(uri));
            if (response.getStatusLine().getStatusCode() != 200) {
                EntityUtils.consume(response.getEntity());
                throw new UnexpectedHttpResponseException("GET", uri, response.getStatusLine());
            }
            return response;
        }

        public Consumer<URI> downloadTo(final File directory) {
            return downloadTo(directory, false);
        }

        public Consumer<URI> downloadTo(final File directory, final boolean overwrite) {
            return uri -> {
                try {
                    final String name = uri.getPath().replaceAll(".*/", "");
                    final File file = new File(directory, name);

                    if (file.exists() && !overwrite) {
                        out.println("Downloaded " + uri);
                    } else {
                        out.println("Downloading " + uri);
                        final CloseableHttpResponse response = get(uri);
                        IO.copy(response.getEntity().getContent(), file);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            };
        }
    }

    public interface Work extends Dir {
        @Walk(maxDepth = 1)
        @Filter(Binary.Format.class)
        Stream<Binary> binaries();
    }

    public interface Binary extends Dir {

        default boolean verifySha1() {
            final String expectedSha1 = slurp(sha1());
            final String actualSha1 = hash("SHA-1");
            return expectedSha1.equals(actualSha1);
        }

        default void createSha256() {
            final String sha256 = hash("SHA-256");
            write(sha256, sha256());
        }

        default void createSha512() {
            final String sha256 = hash("SHA-512");
            write(sha256, sha512());
        }

        default File asc() {
            return get(get(), "asc");
        }

        default File sha1() {
            return get(get(), "sha1");
        }

        default File sha256() {
            return get(get(), "sha256");
        }

        default File sha512() {
            return get(get(), "sha512");
        }

        static void write(String content, File file) {
            try {
                IO.copy(IO.read(content), file);
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to write to file " + file.getAbsolutePath(), e);
            }
        }

        default File get(final File file, final String ext) {
            return new File(file.getParentFile(), file.getName() + "." + ext);
        }

        default String hash(final String type) {
            try {
                final MessageDigest digest = MessageDigest.getInstance(type);
                try (final InputStream inputStream = IO.read(get())) {
                    final DigestInputStream digestInputStream = new DigestInputStream(inputStream, digest);
                    IO.copy(digestInputStream, IO.IGNORE_OUTPUT);
                    return Hex.toString(digest.digest());
                }
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("Unknown algorithm " + type, e);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        static String slurp(final File file) {
            try {
                return IO.slurp(file);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read file " + file.getAbsolutePath(), e);
            }
        }

        class Format implements FileFilter {
            @Override
            public boolean accept(final File file) {
                final String name = file.getName();
                return name.endsWith(".zip") || name.endsWith(".tar.gz");
            }
        }
    }

}
