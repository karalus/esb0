declare namespace v1="http://aoa.de/ei/foundation/v1";
declare function local:copy($element as element()) {
  element {node-name($element)}
    {$element/@*,
     for $child in $element/node()
       return if ($child instance of element())
         then if ($child/node-name() = xs:QName('v1:messageHeader'))
           then <v1:messageHeader>
                    <v1:senderFQN>{$child/v1:senderFQN[1]/text()}</v1:senderFQN>
                    <v1:messageId>{$child/v1:messageId[1]/text()}</v1:messageId>
                    <v1:senderCurrentTimestampUTC>{$child/v1:senderCurrentTimestampUTC[1]/text()}</v1:senderCurrentTimestampUTC>
                    <v1:processInstanceId>{$child/v1:processInstanceId[1]/text()}</v1:processInstanceId>
                    {$child/v1:parentProcessInstanceId[1]}
                    <v1:replyContext>myPort</v1:replyContext>
                 </v1:messageHeader>           
           else local:copy($child)
         else $child
    }
};
local:copy(.)