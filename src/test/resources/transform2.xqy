declare variable $opt as xs:string? external;
declare variable $id as xs:string* external;

<root>
{if (string($opt)!='') then <opt>{$opt}</opt> else ()}
{for $i in $id return <id>{$i}</id>}
</root>