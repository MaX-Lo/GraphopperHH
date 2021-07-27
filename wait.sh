#!/bin/bash

attempt_counter=0
max_attempts=10

until $(curl --output /dev/null --silent --head --fail http://0.0.0.0:8989); do
    if [ ${attempt_counter} -eq ${max_attempts} ];then
      echo "Max attempts reached, preprocessing must be done after creating container"
      exit 1
    fi

    printf '.'
    cat ./log.txt
    attempt_counter=$(($attempt_counter+1))
    sleep 10
done
return 0