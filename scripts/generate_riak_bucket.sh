#!/bin/bash

SIZE="${1:-10}"
QUANTUM="${2:-900}"
DATATYPE="${3:-string}"
COUNT=0

if [[ "$DATATYPE" == "integer" ]]; then
	VALUETYPE="SINT64"
elif [[ "$DATATYPE" == "double" ]]; then
	VALUETYPE="DOUBLE"
else
	VALUETYPE="VARCHAR"
fi

CMD="./riak-admin bucket-type create usertable '{\"props\": {\"w\": 1, \"r\": 1, \"rw\": 1, \"n_val\": ${RIAK_N_VAL:-3}, \"table_def\": \"CREATE TABLE usertable (
		host VARCHAR NOT NULL, 
		worker VARCHAR NOT NULL, 
		time TIMESTAMP NOT NULL"

while [ $COUNT -lt $SIZE ]; do
	CMD="$CMD, field$COUNT $VALUETYPE"
	let COUNT=COUNT+1
done

CMD="$CMD 
	, primary key ((host, worker, quantum(time, $QUANTUM, s)), host, worker, time))\"}}';"

echo $CMD
echo "sleep 60;"
echo "./riak-admin bucket-type activate usertable"