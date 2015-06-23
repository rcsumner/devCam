function outString = fullcommand(varargin)
% outString = fullcommand(str1, str2, ...)
%
% Correctly concatenate string inputs so that they form a valid
% command-line string command for working with the Android adb. Kind of
% like the built-in fullfile() function, but more can add path parts and
% other command parts correctly into one long command.
%
% - - Input - -
% Any number of strings to concatenate.
%
% - - Output - -
% outString : string correctly formatted with the full path and command
%
% Rob Sumner - May 2015

if nargin<2
    error('fullCommand() requires two or more string arguments.')
end


% To combine strings, simply trim any whitespace before or after, then
% combine them in sequence with a single space in between. Note that this
% still actually works (counter-intuitively) when one of the arguments is
% just a space, since it gets trimmed to empty and then added with a space
% before it.
%
% However, if a command piece is a path as indicated by a trailing '/' or
% '\', then don't add a space between it and the next piece. 

outString = varargin{1}; % Don't add a space before first part
for i = 2:nargin
    piece = varargin{i};
    if ischar(piece)
        if (strcmp(outString(end),'/') || strcmp(outString(end),'\'))
            outString = horzcat(outString, strtrim(piece));
        else
            outString = horzcat(outString, ' ', strtrim(piece));
        end
    else
        error('Input arguments must be strings.');
    end
end

end % end function