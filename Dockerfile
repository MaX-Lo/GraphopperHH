# Dockerfile to create a graphhopper image including the latest OSM Hamburg data as well as elevation data
# from Hamburg (dgmhh) or from other elevation provider as set in config.yml.

FROM maven:3.8.4-jdk-8 as build

RUN apt-get install -y wget

WORKDIR /graphhopper

COPY . .

# Use -q to avoid maven overflowing log limits
# Use -e to see stacktraces in case of errors
RUN mvn clean install -DskipTests -q -e

FROM openjdk:11.0-jre

RUN mkdir -p /graphhopper/default-gh

WORKDIR /graphhopper

COPY --from=build /graphhopper/web/target/graphhopper*.jar ./

COPY ./config.yml ./

RUN wget -t 3 "https://download.geofabrik.de/europe/germany/hamburg-latest.osm.pbf"

# preprocessing can only be done in combination with starting graphhopper as well. One solution
# (if not the best) is to start graphhopper and terminate it after preprocessing is finished.
# In this implementation it's done via a timeout, better would be a periodic polling until
# http://localhost:8989 becomes available but for that we would need to start graphhoper as a
# background service which seems not possible with docker(?).:
RUN timeout 60s java -Xmx6g \
         -Xms6g \
         -Ddw.graphhopper.datareader.file=hamburg-latest.osm.pbf \
         -Ddw.graphhopper.graph.location=default-gh \
         -jar /graphhopper/graphhopper-web-3.0-SNAPSHOT.jar \
         server \
         config.yml; exit 0

EXPOSE 8989

ENTRYPOINT java -Xmx6g \
                -Ddw.server.application_connectors[0].bind_host=0.0.0.0 \
                -Ddw.server.application_connectors[0].port=8989 \
                -Ddw.graphhopper.datareader.file=hamburg-latest.osm.pbf \
                -Ddw.graphhopper.graph.location=default-gh \
                -jar /graphhopper/graphhopper-web-3.0-SNAPSHOT.jar \
                server \
                /graphhopper/config.yml