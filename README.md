Maven h3spec test suite Plugin
==============================

A Maven plugin which allows to run the [h3spec](https://github.com/kazu-yamamoto/h3spec) test suite as part of your maven build.

# Requirements
If you want to have the Test suite executed as part of your build you need to provide a Main class which will
startup an HTTP/3 server which has `h3-29` configured as ALPN protocol.


# Adding it to your build
Adding the test suite and make it part of your build can be done by adding the following configuration to your pom.xml
file.
    
    <build>
        <plugins>
          <plugin>
            <groupId>io.netty.incubator</groupId>
            <artifactId>netty-incubator-h3spec-maven-plugin</artifactId>
            <version>0.0.1-SNAPSHOT</version>
            <configuration>
              <mainClass>io.netty.incubator.http3.example.Http3Server</mainClass>
              
              <!-- Optional configuration -->
              <!-- The port to bind the server too. Default is to choose a random free port. -->
              <port>-1</port>
              
              <!-- A list of cases to exclude. Default is none. -->
              <excludeSpecs>
                <excludeSpec>/QUIC</excludeSpec>
              </excludeSpecs>
              
              <skip>${skipH3spec}</skip>
            </configuration>
            <executions>
              <execution>
                <phase>test</phase>
                <goals>
                  <goal>h3spec</goal>
                </goals>
              </execution>
            </executions>
          </plugin>
        </plugins>
      </build>


This will execute the h3spec tests as part of the test phase and fail the phase if one of the test cases
fails.

After the run was complete you will find test-reports in the `target/reports/TEST-h3spec.xml`.


