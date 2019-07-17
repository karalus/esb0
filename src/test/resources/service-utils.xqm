module namespace fn-svi='http://aoa.de/esb/service-utils';

declare namespace v1="http://aoa.de/ei/foundation/v1";

declare function fn-svi:copyAndInsertReplyContext($element as element(), $replyContext as xs:string) {
  element {node-name($element)}
    {$element/@*,
     for $child in $element/node()
       return if ($child instance of element())
         then if ($child/local-name() = 'messageHeader')
           then element {$child/node-name()} {
                    <v1:senderFQN>{$child/v1:senderFQN[1]/text()}</v1:senderFQN>,
                    <v1:messageId>{$child/v1:messageId[1]/text()}</v1:messageId>,
                    <v1:processInstanceId>{$child/v1:processInstanceId[1]/text()}</v1:processInstanceId>,
                    $child/v1:parentProcessInstanceId[1],
                    <v1:replyContext>{$replyContext}</v1:replyContext>
                 }
           else fn-svi:copyAndInsertReplyContext($child, $replyContext)
         else $child
    }
};
