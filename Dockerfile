#
# (C) Copyright IBM Corp. 2016
#
# LICENSE: Eclipse Public License v1.0
# http://www.eclipse.org/legal/epl-v10.html
#

#Get Ubunutu
FROM ubuntu:16.04

#Intall pre-reqs
RUN apt-get update
RUN apt-get -y install default-jre \
                       default-jdk \
                       mosquitto \
                       net-tools \
                       ant

#Set up environment
ENV FABRIC_VERSION=edgware-0.4.1
ENV FABRIC_HOME=/fabric/${FABRIC_VERSION}
ENV PATH=$JAVA_HOME/jre/bin:$PATH:$FABRIC_HOME/bin/linux
ENV JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64/jre

#Copy files and compile build
COPY / /fabricbuild/
RUN tar -xf /fabricbuild/fabric.prereqs/Jetty/jetty-distribution-9.2.9.v20150224.tar.gz -C /fabricbuild/fabric.prereqs/Jetty/
RUN ant -q -f /fabricbuild/fabric.build/build_local.xml

#Copy build to our fabric directory and expand
RUN mkdir fabric
RUN mv /fabricbuild/fabric.build/builds/trunk/latest/*.tar.gz /fabric/
RUN tar -xf /fabric/*.tar.gz -C /fabric

#Add start script and run installer with default parameters
COPY startfabric.sh /fabric/${FABRIC_VERSION}/bin/linux/startfabric
RUN chmod 775 /fabric/${FABRIC_VERSION}/bin/linux/startfabric
RUN /fabric/${FABRIC_VERSION}/fabinstall.sh

#Clean up
RUN rm -rf /fabricbuild
RUN rm /fabric/*.tar.gz

#Expose web server and MQTT broker ports. Set the startfabric script as main process
EXPOSE 8080 1883
ENTRYPOINT ["startfabric"]
