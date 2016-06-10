#!/bin/bash

#
# (C) Copyright IBM Corp. 2016
#
# LICENSE: Eclipse Public License v1.0
# http://www.eclipse.org/legal/epl-v10.html
#

mosquitto --daemon -c /fabric/edgware-0.4.1/etc/broker.conf

if [[ -z $1 ]] ; then
    echo 'Error: No Node name provided'
    echo 'Correct usage example: ./startfabric Node1'
    exit 0
fi

for var in "$@"
do
    fabadmin -s -d -r "$var"
    sleep 2
    fabadmin -i | awk '{ print $3 }' | grep -v ADDRESS | grep -v '\------------' | awk 'NF' | paste -d, -s - | xargs -I {} fabreg -sn "$var" fabric.node.interfaces {}
    fabadmin -s -d -n "$var"
    sleep 2
done

fabadmin -s -w $1
