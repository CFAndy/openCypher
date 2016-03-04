package org.opencypher.grammar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;

abstract class CodePointSet
{
    public static CodePointSet single( int codePoint )
    {
        return new SingleCodePoint( codePoint );
    }

    public static CodePointSet codePoints( int... codePoints )
    {
        if ( codePoints == null || codePoints.length == 0 )
        {
            throw new IllegalArgumentException( "No code points!" );
        }
        if ( codePoints.length == 1 )
        {
            return single( codePoints[0] );
        }
        Arrays.sort( codePoints );
        return new CodePointList( codePoints );
    }

    public static CodePointSet union( CodePointSet... union )
    {
        if ( union == null || union.length == 0 )
        {
            throw new IllegalArgumentException( "Union of nothing!" );
        }
        if ( union.length == 1 )
        {
            return union[0];
        }
        for ( CodePointSet set : union )
        {
            if ( set instanceof Union )
            {
                return Union.flatten( union );
            }
        }
        return new Union( union );
    }

    public static Range range( int start, int end )
    {
        if ( start >= end )
        {
            throw new IllegalArgumentException(
                    new StringBuilder().append( "Invalid range: [" ).appendCodePoint( start ).append( "-" )
                                       .appendCodePoint( end ).append( "]" ).toString() );
        }
        return new Range( start, end );
    }

    public static CodePointSet generalCategory( byte characterType )
    {
        return new GeneralCategory( characterType );
    }

    public static CodePointSet parse( String set )
    {
        class Parser
        {
            int last = -1;
            boolean escape = false, range = false;
            List<Integer> single = new ArrayList<>();
            List<Range> ranges = new ArrayList<>();

            CodePointSet complete()
            {
                if ( range || escape )
                {
                    throw new IllegalArgumentException( "Invalid set notation, cannot end in '-' or '\\': " + set );
                }
                if ( last != -1 )
                {
                    single.add( last );
                }
                if ( single.isEmpty() )
                {
                    return union( ranges.toArray( EMPTY ) );
                }
                CodePointSet[] result = ranges.toArray( new CodePointSet[ranges.size() + 1] );
                if ( single.size() == 1 )
                {
                    result[result.length - 1] = single( single.get( 0 ) );
                }
                else
                {
                    int[] codePoints = new int[single.size()];
                    for ( int i = 0; i < codePoints.length; i++ )
                    {
                        codePoints[i] = single.get( i );
                    }
                    result[result.length - 1] = codePoints( codePoints );
                }
                return union( result );
            }

            void next( int cp )
            {
                if ( range )
                {
                    ranges.add( range( last, cp ) );
                    last = -1;
                }
                else
                {
                    if ( last != -1 )
                    {
                        single.add( last );
                    }
                    last = cp;
                }
                range = escape = false;
            }
        }
        if ( set.charAt( 0 ) != '[' || set.charAt( set.length() - 1 ) != ']' )
        {
            throw new IllegalArgumentException( "Invalid set notation, must be enclosed in '[...]': " + set );
        }
        Parser parser = new Parser();
        for ( int i = 1, end = set.length() - 1, cp; i < end; i += Character.charCount( cp ) )
        {
            cp = set.codePointAt( i );
            switch ( cp )
            {
            case '\\':
                if ( parser.escape )
                {
                    parser.next( cp );
                }
                else
                {
                    parser.escape = true;
                }
                break;
            case '-':
                if ( parser.escape )
                {
                    parser.next( cp );
                }
                else if ( parser.range )
                {
                    throw new IllegalArgumentException( "Invalid set notation, '-' may not follow '-': " + set );
                }
                else if ( parser.last == -1 )
                {
                    throw new IllegalArgumentException(
                            "Invalid set notation, '-' must be preceded by single char: " + set );
                }
                else
                {
                    parser.range = true;
                }
                break;
            case 'a':
            case 'b':
            case 'e':
            case 'f':
            case 'n':
            case 'r':
            case 't':
            case 'v':
                if ( parser.escape )
                {
                    parser.next( escape( cp ) );
                    break;
                }
            default:
                if ( parser.escape )
                {
                    throw new IllegalArgumentException(
                            new StringBuilder().append( "Invalid escape character: " ).appendCodePoint( cp )
                                               .toString() );
                }
                parser.next( cp );
            }
        }
        return parser.complete();
    }

    private static int escape( int cp )
    {
        switch ( cp )
        {   // <pre>
        case 'a': return 0x07;
        case 'b': return '\b';
        case 'e': return 0x1B;
        case 'f': return '\f';
        case 'n': return '\n';
        case 'r': return '\r';
        case 't': return '\t';
        case 'v': return 0x0B;
            //</pre>
        default:
            throw new IllegalArgumentException( new StringBuilder( 2 ).appendCodePoint( cp ).toString() );
        }
    }

    private static final CodePointSet[] EMPTY = {};
    static final CodePointSet ANY = new CodePointSet()
    {
        @Override
        public int hashCode()
        {
            return 0;
        }

        @Override
        public boolean equals( Object obj )
        {
            return obj == this;
        }

        @Override
        public String toString()
        {
            return "ANY";
        }

        @Override
        boolean contains( int cp )
        {
            return Character.isValidCodePoint( cp );
        }
    };
    String name;
    private transient volatile int[] encoded;

    public CodePointSet except( CodePointSet excepted )
    {
        return new Disjunction( CodePointSet.this, excepted );
    }

    @Override
    public abstract int hashCode();

    @Override
    public abstract boolean equals( Object obj );

    @Override
    public abstract String toString();

    abstract boolean contains( int cp );

    @SuppressWarnings("unchecked")
    <EX extends Exception> void accept( CharacterSet.DefinitionVisitor<EX> visitor ) throws EX
    {
        if ( name != null && visitor instanceof CharacterSet.DefinitionVisitor.NamedSetVisitor<?> )
        {
            ((CharacterSet.DefinitionVisitor.NamedSetVisitor<EX>) visitor).visitSet( name ).close();
            return;
        }
        int[] encoded = this.encoded;
        if ( encoded == null )
        {
            synchronized ( this )
            {
                if ( (encoded = this.encoded) == null )
                {
                    this.encoded = encoded = encode();
                }
            }
        }
        decode( encoded, visitor );
    }

    private <EX extends Exception> void visit( CharacterSet.DefinitionVisitor<EX> visitor ) throws EX
    {
        boolean include = false;
        int first, cp;
        for ( cp = Character.MIN_CODE_POINT, first = -1; cp <= Character.MAX_CODE_POINT; cp++ )
        {
            if ( contains( cp ) )
            {
                if ( !include )
                {
                    first = cp;
                }
                include = true;
            }
            else if ( include )
            {
                visit( visitor, first, cp );
                include = false;
            }
        }
        if ( include )
        {
            visit( visitor, first, cp );
        }
    }

    private <EX extends Exception> void decode( int[] encoded, CharacterSet.DefinitionVisitor<EX> visitor ) throws EX
    {
        int rangeBound = encoded[0] * 2, single = rangeBound + 1;
        for ( int range = 1; range < rangeBound; range += 2 )
        {
            int start = encoded[range], end = encoded[range + 1];
            while ( single < encoded.length && encoded[single] < start )
            {
                visitor.visitCodePoint( encoded[single++] );
            }
            visitor.visitRange( start, end );
        }
        while ( single < encoded.length )
        {
            visitor.visitCodePoint( encoded[single++] );
        }
    }

    private int[] encode()
    {
        List<Integer> single = new ArrayList<>();
        List<Range> ranges = new ArrayList<>();
        visit( new CharacterSet.DefinitionVisitor<RuntimeException>()
        {
            @Override
            public void visitCodePoint( int cp )
            {
                single.add( cp );
            }

            @Override
            public void visitRange( int start, int end )
            {
                ranges.add( range( start, end ) );
            }
        } );
        return encode( ranges, single );
    }

    private int[] encode( List<Range> ranges, List<Integer> single )
    {
        int[] encoded = new int[1 + ranges.size() * 2 + single.size()];
        int i;
        encoded[i = 0] = ranges.size();
        for ( Range range : ranges )
        {
            encoded[++i] = range.start;
            encoded[++i] = range.end;
        }
        for ( int codePoint : single )
        {
            encoded[++i] = codePoint;
        }
        return encoded;
    }

    private static <EX extends Exception> void visit( CharacterSet.DefinitionVisitor<EX> visitor, int first, int next )
            throws EX
    {
        if ( next == first + 1 )
        {
            visitor.visitCodePoint( first );
        }
        else
        {
            visitor.visitRange( first, next - 1 );
        }
    }

    public int randomCodePoint( Random random )
    {
        int cp;
        do
        {
            cp = random.nextInt( Character.MAX_CODE_POINT );
        } while ( !contains( cp ) );
        return cp;
    }

    private static final class Disjunction extends CodePointSet
    {
        private final CodePointSet excluded;
        private final CodePointSet included;

        Disjunction( CodePointSet included, CodePointSet excluded )
        {
            this.excluded = excluded;
            this.included = included;
        }

        @Override
        public CodePointSet except( CodePointSet more )
        {
            return included.except( union( excluded, more ) );
        }

        @Override
        public int hashCode()
        {
            return Objects.hash( included, excluded );
        }

        @Override
        public boolean equals( Object obj )
        {
            if ( this == obj )
            {
                return true;
            }
            if ( obj.getClass() != Disjunction.class )
            {
                return false;
            }
            Disjunction that = (Disjunction) obj;
            return included.equals( that.included ) && excluded.equals( that.excluded );
        }

        @Override
        boolean contains( int cp )
        {
            return included.contains( cp ) && !excluded.contains( cp );
        }

        @Override
        <EX extends Exception> void accept( CharacterSet.DefinitionVisitor<EX> visitor ) throws EX
        {
            if ( name == null && included.name != null &&
                 visitor instanceof CharacterSet.DefinitionVisitor.NamedSetVisitor<?> )
            {
                visit( included.name, excluded, (CharacterSet.DefinitionVisitor.NamedSetVisitor<EX>) visitor );
            }
            else
            {
                super.accept( visitor );
            }
        }

        private static <EX extends Exception> void visit(
                String base, CodePointSet excluded, CharacterSet.DefinitionVisitor.NamedSetVisitor<EX> x ) throws EX
        {
            try ( CharacterSet.ExclusionVisitor<EX> ex = x.visitSet( base ) )
            {
                excluded.accept( new CharacterSet.DefinitionVisitor<EX>()
                {
                    @Override
                    public void visitCodePoint( int cp ) throws EX
                    {
                        ex.excludeCodePoint( cp );
                    }

                    @Override
                    public void visitRange( int start, int end ) throws EX
                    {
                        ex.excludeRange( start, end );
                    }
                } );
            }
        }

        @Override
        public String toString()
        {
            return included + "--" + excluded;
        }
    }

    private static final class Union extends CodePointSet
    {
        private final CodePointSet[] union;

        Union( CodePointSet[] union )
        {
            this.union = union;
        }

        @Override
        boolean contains( int cp )
        {
            for ( CodePointSet set : union )
            {
                if ( set.contains( cp ) )
                {
                    return true;
                }
            }
            return false;
        }

        @Override
        public int hashCode()
        {
            return Objects.hash( union );
        }

        @Override
        public boolean equals( Object obj )
        {
            if ( this == obj )
            {
                return true;
            }
            if ( obj.getClass() != Union.class )
            {
                return false;
            }
            Union that = (Union) obj;
            return Arrays.equals( this.union, that.union );
        }

        @Override
        public String toString()
        {
            StringBuilder result = new StringBuilder().append( "[" );
            for ( CodePointSet set : union )
            {
                result.append( set );
            }
            return result.append( "]" ).toString();
        }

        static CodePointSet flatten( CodePointSet[] union )
        {
            List<CodePointSet> flat = new ArrayList<>();
            for ( CodePointSet set : union )
            {
                if ( set instanceof Union )
                {
                    Collections.addAll( flat, ((Union) set).union );
                }
                else
                {
                    flat.add( set );
                }
            }
            return new Union( flat.toArray( EMPTY ) );
        }
    }

    private static final class Range extends CodePointSet
    {
        private final int start;
        private final int end;

        Range( int start, int end )
        {
            this.start = start;
            this.end = end;
        }

        @Override
        boolean contains( int cp )
        {
            return start <= cp && cp <= end;
        }

        @Override
        <EX extends Exception> void accept( CharacterSet.DefinitionVisitor<EX> visitor ) throws EX
        {
            visitor.visitRange( start, end );
        }

        @Override
        public int randomCodePoint( Random random )
        {
            return start + random.nextInt( end - start + 1 );
        }

        @Override
        public int hashCode()
        {
            return start;
        }

        @Override
        public boolean equals( Object obj )
        {
            if ( this == obj )
            {
                return true;
            }
            if ( obj.getClass() != Range.class )
            {
                return false;
            }
            Range that = (Range) obj;
            return this.start == that.start && this.end == that.end;
        }

        @Override
        public String toString()
        {
            return String.format( "[\\u%04X-\\u%04X]", start, end );
        }
    }

    private static final class SingleCodePoint extends CodePointSet
    {
        private final int codePoint;

        SingleCodePoint( int codePoint )
        {
            this.codePoint = codePoint;
        }

        @Override
        public int hashCode()
        {
            return codePoint;
        }

        @Override
        public boolean equals( Object obj )
        {
            if ( this == obj )
            {
                return true;
            }
            if ( obj.getClass() != SingleCodePoint.class )
            {
                return false;
            }
            SingleCodePoint that = (SingleCodePoint) obj;
            return this.codePoint == that.codePoint;
        }

        @Override
        public String toString()
        {
            return String.format( "[\\u%04X]", codePoint );
        }

        @Override
        boolean contains( int cp )
        {
            return cp == codePoint;
        }

        @Override
        public int randomCodePoint( Random random )
        {
            return codePoint;
        }
    }

    private static final class CodePointList extends CodePointSet
    {
        private final int[] codePoints;

        CodePointList( int... codePoints )
        {
            this.codePoints = codePoints;
        }

        @Override
        boolean contains( int cp )
        {
            for ( int codePoint : codePoints )
            {
                if ( cp == codePoint )
                {
                    return true;
                }
            }
            return false;
        }

        @Override
        <EX extends Exception> void accept( CharacterSet.DefinitionVisitor<EX> visitor ) throws EX
        {
            for ( int cp : codePoints )
            {
                visitor.visitCodePoint( cp );
            }
        }

        @Override
        public int randomCodePoint( Random random )
        {
            return codePoints[random.nextInt( codePoints.length )];
        }

        @Override
        public int hashCode()
        {
            return Arrays.hashCode( codePoints );
        }

        @Override
        public boolean equals( Object obj )
        {
            if ( this == obj )
            {
                return true;
            }
            if ( obj.getClass() != CodePointList.class )
            {
                return false;
            }
            CodePointList that = (CodePointList) obj;
            return Arrays.equals( this.codePoints, that.codePoints );
        }

        @Override
        public String toString()
        {
            StringBuilder result = new StringBuilder();
            char sep = '[';
            for ( int cp : codePoints )
            {
                result.append( sep ).append( String.format( "\\u%04X", cp ) );
                sep = ',';
            }
            return result.append( ']' ).toString();
        }
    }

    private static final class GeneralCategory extends CodePointSet
    {
        private final byte characterType;

        GeneralCategory( byte characterType )
        {
            this.characterType = characterType;
        }

        @Override
        boolean contains( int cp )
        {
            return Character.getType( cp ) == characterType;
        }

        @Override
        public int hashCode()
        {
            return Character.MAX_CODE_POINT + characterType;
        }

        @Override
        public boolean equals( Object obj )
        {
            if ( this == obj )
            {
                return true;
            }
            if ( obj.getClass() != GeneralCategory.class )
            {
                return false;
            }
            GeneralCategory that = (GeneralCategory) obj;
            return this.characterType == that.characterType;
        }

        @Override
        public String toString()
        {
            return "[:" + CharacterSet.Unicode.fromCharacterType( characterType ).name() + ":]";
        }
    }
}
